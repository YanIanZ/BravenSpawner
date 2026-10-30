package dev.yanianz.bravenspawner.command;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /bspawner, /bmodify, /bhtoggle. */
public final class CommandHandler implements CommandExecutor, TabCompleter {

    private final BravenSpawner plugin;

    public CommandHandler(BravenSpawner plugin) {
        this.plugin = plugin;
    }

    /**
     * Binds this handler to every command declared in plugin.yml.
     *
     * <p>The bootstrap only wires {@code /bspawner} historically, while plugin.yml
     * also declares {@code /bmodify} and {@code /bhtoggle}; the server then answers
     * those two with "unknown command" even though the help text advertises them.
     * Registering from one list keeps the yml, the switch below and tab completion
     * in sync, and a missing declaration is logged rather than silently ignored.
     */
    public void register(Plugin owner) {
        if (!(owner instanceof JavaPlugin javaPlugin)) {
            throw new IllegalArgumentException("command registration needs a JavaPlugin");
        }
        for (String name : List.of("bspawner", "bmodify", "bhtoggle")) {
            PluginCommand command = javaPlugin.getCommand(name);
            if (command == null) {
                javaPlugin.getLogger().warning("plugin.yml declares no /" + name + " command.");
                continue;
            }
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    /**
     * Resolves a label or alias to the canonical command name.
     *
     * <p>Bukkit normally reports the primary name in {@link Command#getName()},
     * but accepting the aliases too keeps the switch correct if an alias is ever
     * registered independently or replayed by another plugin.
     */
    private static String canonical(String name) {
        if (name == null) return "";
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "bravenspawner", "vs", "bs" -> "bspawner";
            case "bsmodify" -> "bmodify";
            case "bstoggle" -> "bhtoggle";
            default -> name.toLowerCase(Locale.ROOT);
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!plugin.ready()) {
            sender.sendMessage(Text.of("&cSpawner storage is still loading."));
            return true;
        }
        return switch (canonical(command.getName())) {
            case "bspawner" -> admin(sender, args);
            case "bmodify" -> modify(sender, args);
            case "bhtoggle" -> toggle(sender);
            default -> false;
        };
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bravenspawner.admin")) {
            sender.sendMessage(Text.of("&cNo permission."));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Text.of("&e/bspawner give|list|reload|booster|boosteritem|sellwand|modifier|menu|corrupt|repair"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(sender, args);
            case "list" -> list(sender);
            case "doctor" -> {
                doctor(sender);
                return true;
            }
            case "reload" -> {
                plugin.reloadAll();
                sender.sendMessage(Text.of("&aReloaded."));
            }
            case "booster" -> booster(sender, args);
            case "boosteritem" -> boosterItem(sender, args);
            case "sellwand" -> grantWand(sender, args);
            case "modifier" -> grantModifier(sender, args);
            case "menu" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Text.of("&cPlayers only."));
                    return true;
                }
                Spawner spawner = nearest(p);
                if (spawner != null) plugin.gui().openMenu(p, spawner);
            }
            case "corrupt" -> corrupt(sender);
            case "repair" -> repair(sender);
            default -> sender.sendMessage(Text.of("&cUnknown subcommand."));
        }
        return true;
    }

    private Spawner nearest(Player player) {
        Spawner spawner = plugin.manager().ownedBy(player.getUniqueId()).stream().findFirst().orElse(null);
        if (spawner == null) player.sendMessage(Text.of("&7You have no spawners."));
        return spawner;
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.of("&e/bspawner give <mob|item:ID> [player] [stack]"));
            return;
        }
        String raw = args[1].toUpperCase(Locale.ROOT);
        Spawner.Kind kind = raw.startsWith("ITEM:") ? Spawner.Kind.ITEM : Spawner.Kind.MOB;
        String product = raw.startsWith("ITEM:") ? raw.substring(5) : raw;
        boolean known = kind == Spawner.Kind.MOB
                ? plugin.settings().mobs().containsKey(product)
                : plugin.settings().items().containsKey(product);
        if (!known) {
            sender.sendMessage(Text.of("&cUnknown product: " + product));
            return;
        }
        Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2])
                : sender instanceof Player p ? p : null;
        if (target == null) {
            sender.sendMessage(Text.of("&cPlayer not found."));
            return;
        }
        int stack = 1;
        if (args.length > 3) {
            try {
                stack = Math.max(1, Integer.parseInt(args[3]));
            } catch (NumberFormatException ignored) {
            }
        }
        ItemStack item = plugin.createSpawnerItem(kind, product, stack);
        int units = stack;
        dev.yanianz.bravenspawner.util.Schedulers.onEntity(plugin, target, () -> {
            var leftover = target.getInventory().addItem(item);
            leftover.values().forEach(l -> target.getWorld().dropItemNaturally(target.getLocation(), l));
            target.sendMessage(Text.of("&aReceived &f" + product + " &ax" + units));
        }, () -> { });
    }

    /**
     * One command that answers "why is nothing spawning": backend, load state,
     * catalogue size and the live spawner count, each on its own line.
     */
    private void doctor(CommandSender sender) {
        var p = plugin;
        sender.sendMessage(Text.of("&8— &bBravenSpawner doctor &8—"));
        sender.sendMessage(Text.of("&7storage    : &f" + p.settings().storageType));
        sender.sendMessage(Text.of("&7loaded     : &f" + p.ready()));
        sender.sendMessage(Text.of("&7spawners   : &f" + p.manager().size()));
        sender.sendMessage(Text.of("&7catalogue  : &f" + p.products().allMobs().size()
                + " mobs, " + p.products().allItems().size() + " items"));
        sender.sendMessage(Text.of("&7economy    : &f" + p.economy().enabled()));
        sender.sendMessage(Text.of("&7tiers      : &f" + p.settings().tiers.size()
                + " (base interval " + p.settings().tiers.get(0).intervalTicks + "t)"));
        var firstMob = p.products().allMobs().stream().findFirst().orElse(null);
        sender.sendMessage(Text.of("&7sample     : &f" + (firstMob == null ? "none — catalogue empty"
                : firstMob.id() + " -> " + firstMob.entityType() + " every "
                  + firstMob.intervalTicks() + "t")));
        if (p.products().allMobs().isEmpty()) {
            sender.sendMessage(Text.of("&cCatalogue empty: check plugins/BravenSpawner/mobs/*.yml"));
        }
    }

    private void list(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.of("&7Total spawners: " + plugin.manager().size()));
            return;
        }
        List<Spawner> own = plugin.manager().ownedBy(player.getUniqueId());
        sender.sendMessage(Text.of("&eSpawners: &f" + own.size()));
        own.forEach(s -> sender.sendMessage(Text.of(String.format(
                " &7- &f%s &7stack %d, stored %d, %s %d,%d,%d",
                s.product(), s.stack(), s.storedCount(), s.world(), s.x(), s.y(), s.z()))));
    }

    private void booster(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.of("&e/bspawner booster <player> <multiplier> [seconds]"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Text.of("&cPlayer not found."));
            return;
        }
        double multiplier;
        try {
            multiplier = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Text.of("&cInvalid multiplier."));
            return;
        }
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            sender.sendMessage(Text.of("&cMultiplier must be a positive number."));
            return;
        }
        long seconds = plugin.settings().boosterSeconds();
        if (args.length > 3) {
            try {
                seconds = Long.parseLong(args[3]);
            } catch (NumberFormatException ignored) {
            }
        }
        seconds = Math.max(5L, Math.min(86_400L, seconds));
        plugin.boosters().grantPlayer(target.getUniqueId(), multiplier, seconds);
        sender.sendMessage(Text.of("&aBooster " + multiplier + "x for " + target.getName()
                + " &7(" + seconds + "s)"));
    }

    private void boosterItem(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Text.of("&e/bspawner boosteritem <player> <normal|personal> <id> [amount]"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Text.of("&cPlayer not found."));
            return;
        }
        dev.yanianz.bravenspawner.boosters.Boosters.Scope scope;
        try { scope = dev.yanianz.bravenspawner.boosters.Boosters.Scope.valueOf(args[2].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) {
            sender.sendMessage(Text.of("&cScope must be normal or personal."));
            return;
        }
        int amount = grantAmount(sender, args, 4);
        if (amount == 0) return;
        if (!plugin.boosters().giveItem(target, scope, args[3], amount)) {
            sender.sendMessage(Text.of("&cUnknown booster id or boosters are disabled."));
            return;
        }
        sender.sendMessage(Text.of("&aScheduled booster item delivery to " + target.getName() + "."));
    }

    private void grantWand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.of("&e/bspawner sellwand <player> [uses|Nd|inf] [amount]"));
            return;
        }
        var wands = plugin.wands();
        if (wands == null || !wands.enabled()) {
            sender.sendMessage(Text.of("&cSell wands are disabled."));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Text.of("&cPlayer not found."));
            return;
        }
        String duration = args.length > 2 ? args[2] : "inf";
        try { dev.yanianz.bravenspawner.wand.WandLifetime.parse(duration, System.currentTimeMillis()); }
        catch (IllegalArgumentException exception) {
            sender.sendMessage(Text.of("&cInvalid duration. Use a positive number of uses, Nd for days, or inf."));
            return;
        }
        int amount = grantAmount(sender, args, 3);
        if (amount == 0) return;
        dev.yanianz.bravenspawner.util.Schedulers.onEntity(plugin, target, () -> {
            for (int index = 0; index < amount; index++) {
                // A fresh identity per item avoids joining independently granted wands.
                ItemStack item = wands.createWand(duration);
                if (item != null) giveItem(target, item);
            }
            target.sendMessage(Text.of("&aReceived " + amount + " sell wand(s)."));
        }, null);
        sender.sendMessage(Text.of("&aScheduled sell wand delivery to " + target.getName() + "."));
    }

    private void grantModifier(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Text.of("&e/bspawner modifier <player> <range|price_multiplier> <value> [amount]"));
            return;
        }
        var wands = plugin.wands();
        if (wands == null || !wands.enabled()) {
            sender.sendMessage(Text.of("&cSell wands are disabled."));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Text.of("&cPlayer not found."));
            return;
        }
        double value;
        try { value = Double.parseDouble(args[3]); }
        catch (NumberFormatException exception) { value = Double.NaN; }
        if (!Double.isFinite(value) || value < 0) {
            sender.sendMessage(Text.of("&cValue must be a finite nonnegative number."));
            return;
        }
        var modifier = wands.modifier(args[2], value);
        if (modifier == null) {
            sender.sendMessage(Text.of("&cUnknown modifier type."));
            return;
        }
        int amount = grantAmount(sender, args, 4);
        if (amount == 0) return;
        dev.yanianz.bravenspawner.util.Schedulers.onEntity(plugin, target, () -> {
            ItemStack item = wands.createModifier(modifier);
            if (item == null) return;
            item.setAmount(amount);
            giveItem(target, item);
            target.sendMessage(Text.of("&aReceived modifiers. Place one on a sell wand in your inventory to apply it."));
        }, null);
        sender.sendMessage(Text.of("&aScheduled modifier delivery to " + target.getName() + "."));
    }

    private int grantAmount(CommandSender sender, String[] args, int index) {
        if (args.length <= index) return 1;
        try {
            int amount = Integer.parseInt(args[index]);
            if (amount >= 1 && amount <= 64) return amount;
        } catch (NumberFormatException ignored) { }
        sender.sendMessage(Text.of("&cAmount must be between 1 and 64."));
        return 0;
    }

    private void giveItem(Player player, ItemStack item) {
        player.getInventory().addItem(item).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private void corrupt(CommandSender sender) {
        if (!(sender instanceof Player player)) return;
        Spawner spawner = nearest(player);
        if (spawner == null) return;
        spawner.corrupt();
        plugin.markDirty(spawner);
        plugin.record(spawner, dev.yanianz.bravenspawner.audit.AuditEvent.Action.CORRUPT, player, 0, "command");
        player.sendMessage(Text.of("&cSpawner corrupted."));
    }

    private void repair(CommandSender sender) {
        if (!(sender instanceof Player player)) return;
        Spawner spawner = nearest(player);
        if (spawner == null) return;
        spawner.repair();
        plugin.markDirty(spawner);
        plugin.record(spawner, dev.yanianz.bravenspawner.audit.AuditEvent.Action.REPAIR, player, 0, "command");
        player.sendMessage(Text.of("&aSpawner repaired."));
    }

    private boolean modify(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.of("&cPlayers only."));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Text.of("&e/bmodify <player>"));
            return true;
        }
        var block = player.getTargetBlockExact(6);
        Spawner spawner = block == null ? null : plugin.manager().at(block.getLocation());
        if (spawner == null) {
            player.sendMessage(Text.of("&cLook at one of your spawners."));
            return true;
        }
        if (!spawner.isOwner(player.getUniqueId()) && !player.hasPermission("bravenspawner.admin")) {
            player.sendMessage(Text.of("&cOnly the owner can manage trust."));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage(Text.of("&cPlayer not online."));
            return true;
        }
        UUID id = target.getUniqueId();
        if (spawner.trusted().remove(id)) {
            player.sendMessage(Text.of("&cRemoved trust: " + target.getName()));
        } else {
            spawner.trusted().add(id);
            player.sendMessage(Text.of("&aTrusted: " + target.getName()));
        }
        plugin.markDirty(spawner);
        return true;
    }

    private boolean toggle(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.of("&cPlayers only."));
            return true;
        }
        List<Spawner> own = plugin.manager().ownedBy(player.getUniqueId());
        if (own.isEmpty()) {
            player.sendMessage(Text.of("&7You have no spawners."));
            return true;
        }
        boolean state = !own.get(0).hologram();
        own.forEach(s -> {
            s.hologram(state);
            plugin.markDirty(s);
            plugin.holograms().update(s);
        });
        player.sendMessage(Text.of("&eHolograms: " + (state ? "on" : "off")));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        switch (canonical(command.getName())) {
            case "bspawner" -> {
                if (!sender.hasPermission("bravenspawner.admin")) return List.of();
                if (args.length == 1) {
                    out.addAll(List.of("give", "list", "reload", "booster", "boosteritem", "sellwand", "modifier", "menu", "corrupt", "repair"));
                } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
                    out.addAll(plugin.settings().mobs().keySet());
                    plugin.settings().items().keySet().forEach(i -> out.add("item:" + i));
                } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
                    Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                } else if (args.length == 3 && args[0].equalsIgnoreCase("boosteritem")) {
                    out.addAll(List.of("normal", "personal"));
                } else if (args.length == 4 && args[0].equalsIgnoreCase("boosteritem")) {
                    try {
                        var scope = dev.yanianz.bravenspawner.boosters.Boosters.Scope.valueOf(args[2].toUpperCase(Locale.ROOT));
                        out.addAll(plugin.boosters().itemIds(scope));
                    } catch (IllegalArgumentException ignored) { }
                } else if (args.length == 3 && args[0].equalsIgnoreCase("modifier") && plugin.wands() != null) {
                    plugin.wands().modifierTypes().forEach(type -> out.add(type.id()));
                } else if (args.length == 2 && List.of("booster", "boosteritem", "sellwand", "modifier").contains(args[0].toLowerCase(Locale.ROOT))) {
                    Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                }
            }
            case "bmodify" -> {
                if (args.length <= 1) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            }
            default -> { }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
