package dev.yanianz.bravenspawner.command;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Messages;
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

    private Messages messages() {
        return Messages.of(plugin);
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
            messages().send(sender, "general.not-ready");
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
            messages().send(sender, "general.no-permission");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            messages().sendLines(sender, "help");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(sender, args);
            case "list" -> list(sender);
            case "doctor" -> {
                doctor(sender);
                return true;
            }
            case "reload" -> reload(sender);
            case "booster" -> booster(sender, args);
            case "boosteritem" -> boosterItem(sender, args);
            case "sellwand" -> grantWand(sender, args);
            case "modifier" -> grantModifier(sender, args);
            case "menu" -> {
                if (!(sender instanceof Player p)) {
                    messages().send(sender, "general.players-only");
                    return true;
                }
                Spawner spawner = targeted(p);
                if (spawner != null) plugin.gui().openMenu(p, spawner);
            }
            case "corrupt" -> corrupt(sender);
            case "repair" -> repair(sender);
            default -> messages().send(sender, "general.unknown-subcommand", "sub", args[0]);
        }
        return true;
    }

    /**
     * Reloads everything, reporting a refused reload (storage not ready, or the
     * pre-reload save failed) as a message instead of an internal error.
     */
    private void reload(CommandSender sender) {
        try {
            plugin.reloadAll();
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Reload refused: " + failure);
            messages().send(sender, "reload.failed", "reason",
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            return;
        }
        messages().send(sender, "reload.success");
    }

    /** The managed spawner the player is looking at, within six blocks. */
    private Spawner targeted(Player player) {
        var block = player.getTargetBlockExact(6);
        Spawner spawner = block == null ? null : plugin.manager().at(block.getLocation());
        if (spawner == null) messages().send(player, "general.look-at-spawner");
        return spawner;
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages().send(sender, "give.usage");
            return;
        }
        String raw = args[1].toUpperCase(Locale.ROOT);
        Spawner.Kind kind = raw.startsWith("ITEM:") ? Spawner.Kind.ITEM : Spawner.Kind.MOB;
        String product = raw.startsWith("ITEM:") ? raw.substring(5) : raw;
        boolean known = kind == Spawner.Kind.MOB
                ? plugin.settings().mobs().containsKey(product)
                : plugin.settings().items().containsKey(product);
        if (!known) {
            messages().send(sender, "give.unknown-product", "product", product);
            return;
        }
        Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2])
                : sender instanceof Player p ? p : null;
        if (target == null) {
            messages().send(sender, "general.player-not-found", "player", args.length > 2 ? args[2] : "?");
            return;
        }
        int stack = 1;
        if (args.length > 3) {
            try {
                stack = Integer.parseInt(args[3]);
            } catch (NumberFormatException invalid) {
                stack = 0;
            }
            if (stack < 1) {
                messages().send(sender, "give.invalid-stack");
                return;
            }
        }
        ItemStack item = plugin.createSpawnerItem(kind, product, stack);
        int units = stack;
        dev.yanianz.bravenspawner.util.Schedulers.onEntity(plugin, target, () -> {
            var leftover = target.getInventory().addItem(item);
            leftover.values().forEach(l -> target.getWorld().dropItemNaturally(target.getLocation(), l));
            messages().send(target, "give.received", "amount", units, "product", product);
        }, () -> { });
        if (sender != target) {
            messages().send(sender, "give.sent", "amount", units, "product", product, "player", target.getName());
        }
    }

    /**
     * One command that answers "why is nothing spawning": backend, load state,
     * catalogue size and the live spawner count, each on its own line.
     */
    private void doctor(CommandSender sender) {
        var p = plugin;
        Messages m = messages();
        m.send(sender, "doctor.header");
        m.sendPlain(sender, "doctor.line", "key", "storage", "value", p.settings().storageType);
        m.sendPlain(sender, "doctor.line", "key", "loaded", "value", p.ready());
        m.sendPlain(sender, "doctor.line", "key", "spawners", "value", p.manager().size());
        m.sendPlain(sender, "doctor.line", "key", "catalogue", "value", p.products().allMobs().size()
                + " mobs, " + p.products().allItems().size() + " items");
        m.sendPlain(sender, "doctor.line", "key", "economy", "value", p.economy().enabled());
        m.sendPlain(sender, "doctor.line", "key", "tiers", "value", p.settings().tiers.size()
                + (p.settings().tiers.isEmpty() ? "" : " (base interval " + p.settings().tiers.get(0).intervalTicks + "t)"));
        m.sendPlain(sender, "doctor.line", "key", "holograms", "value",
                p.holograms() != null && p.holograms().config().enabled() ? "enabled" : "disabled");
        var firstMob = p.products().allMobs().stream().findFirst().orElse(null);
        m.sendPlain(sender, "doctor.line", "key", "sample", "value", firstMob == null ? "none — catalogue empty"
                : firstMob.id() + " -> " + firstMob.entityType() + " every " + firstMob.intervalTicks() + "t");
        if (p.products().allMobs().isEmpty()) m.send(sender, "doctor.empty-catalogue");
    }

    private void list(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "list.console", "count", plugin.manager().size());
            return;
        }
        List<Spawner> own = plugin.manager().ownedBy(player.getUniqueId());
        messages().send(sender, "list.header", "count", own.size());
        own.forEach(s -> messages().sendPlain(sender, "list.entry", "product", s.product(), "stack", s.stack(),
                "stored", s.storedCount(), "world", s.world(), "x", s.x(), "y", s.y(), "z", s.z()));
    }

    private void booster(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages().send(sender, "booster.usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages().send(sender, "general.player-not-found", "player", args[1]);
            return;
        }
        double multiplier;
        try {
            multiplier = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            multiplier = Double.NaN;
        }
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            messages().send(sender, "booster.invalid-multiplier");
            return;
        }
        long seconds = plugin.settings().boosterSeconds();
        if (args.length > 3) {
            try {
                seconds = Long.parseLong(args[3]);
            } catch (NumberFormatException invalid) {
                messages().send(sender, "booster.invalid-seconds", "seconds", Math.max(5L, Math.min(86_400L, seconds)));
            }
        }
        seconds = Math.max(5L, Math.min(86_400L, seconds));
        plugin.boosters().grantPlayer(target.getUniqueId(), multiplier, seconds);
        messages().send(sender, "booster.granted", "multiplier", multiplier, "player", target.getName(),
                "seconds", seconds);
    }

    private void boosterItem(CommandSender sender, String[] args) {
        if (args.length < 4) {
            messages().send(sender, "booster-item.usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages().send(sender, "general.player-not-found", "player", args[1]);
            return;
        }
        dev.yanianz.bravenspawner.boosters.Boosters.Scope scope;
        try { scope = dev.yanianz.bravenspawner.boosters.Boosters.Scope.valueOf(args[2].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) {
            messages().send(sender, "booster-item.invalid-scope");
            return;
        }
        int amount = grantAmount(sender, args, 4);
        if (amount == 0) return;
        if (!plugin.boosters().giveItem(target, scope, args[3], amount)) {
            messages().send(sender, "booster-item.unknown");
            return;
        }
        messages().send(sender, "booster-item.scheduled", "player", target.getName());
    }

    private void grantWand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages().send(sender, "sellwand.usage");
            return;
        }
        var wands = plugin.wands();
        if (wands == null || !wands.enabled()) {
            messages().send(sender, "sellwand.disabled");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages().send(sender, "general.player-not-found", "player", args[1]);
            return;
        }
        String duration = args.length > 2 ? args[2] : "inf";
        try { dev.yanianz.bravenspawner.wand.WandLifetime.parse(duration, System.currentTimeMillis()); }
        catch (IllegalArgumentException exception) {
            messages().send(sender, "sellwand.invalid-duration");
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
            messages().send(target, "sellwand.received", "amount", amount);
        }, null);
        messages().send(sender, "sellwand.scheduled", "player", target.getName());
    }

    private void grantModifier(CommandSender sender, String[] args) {
        if (args.length < 4) {
            messages().send(sender, "modifier.usage");
            return;
        }
        var wands = plugin.wands();
        if (wands == null || !wands.enabled()) {
            messages().send(sender, "sellwand.disabled");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages().send(sender, "general.player-not-found", "player", args[1]);
            return;
        }
        double value;
        try { value = Double.parseDouble(args[3]); }
        catch (NumberFormatException exception) { value = Double.NaN; }
        if (!Double.isFinite(value) || value < 0) {
            messages().send(sender, "modifier.invalid-value");
            return;
        }
        var modifier = wands.modifier(args[2], value);
        if (modifier == null) {
            messages().send(sender, "modifier.unknown-type");
            return;
        }
        int amount = grantAmount(sender, args, 4);
        if (amount == 0) return;
        dev.yanianz.bravenspawner.util.Schedulers.onEntity(plugin, target, () -> {
            ItemStack item = wands.createModifier(modifier);
            if (item == null) return;
            item.setAmount(amount);
            giveItem(target, item);
            messages().send(target, "modifier.received");
        }, null);
        messages().send(sender, "modifier.scheduled", "player", target.getName());
    }

    private int grantAmount(CommandSender sender, String[] args, int index) {
        if (args.length <= index) return 1;
        try {
            int amount = Integer.parseInt(args[index]);
            if (amount >= 1 && amount <= 64) return amount;
        } catch (NumberFormatException ignored) { }
        messages().send(sender, "general.amount-range");
        return 0;
    }

    private void giveItem(Player player, ItemStack item) {
        player.getInventory().addItem(item).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private void corrupt(CommandSender sender) {
        if (!(sender instanceof Player player)) return;
        Spawner spawner = targeted(player);
        if (spawner == null) return;
        spawner.corrupt();
        plugin.markDirty(spawner);
        plugin.record(spawner, dev.yanianz.bravenspawner.audit.AuditEvent.Action.CORRUPT, player, 0, "command");
        messages().send(player, "corrupt.done");
    }

    private void repair(CommandSender sender) {
        if (!(sender instanceof Player player)) return;
        Spawner spawner = targeted(player);
        if (spawner == null) return;
        spawner.repair();
        plugin.markDirty(spawner);
        plugin.record(spawner, dev.yanianz.bravenspawner.audit.AuditEvent.Action.REPAIR, player, 0, "command");
        messages().send(player, "repair.done");
    }

    private boolean modify(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "general.players-only");
            return true;
        }
        if (args.length == 0) {
            messages().send(player, "modify.usage");
            return true;
        }
        Spawner spawner = targeted(player);
        if (spawner == null) return true;
        if (!spawner.isOwner(player.getUniqueId()) && !player.hasPermission("bravenspawner.admin")) {
            messages().send(player, "modify.not-owner");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages().send(player, "general.player-not-found", "player", args[0]);
            return true;
        }
        UUID id = target.getUniqueId();
        if (spawner.trusted().remove(id)) {
            messages().send(player, "modify.untrusted", "player", target.getName());
        } else {
            spawner.trusted().add(id);
            messages().send(player, "modify.trusted", "player", target.getName());
        }
        plugin.markDirty(spawner);
        return true;
    }

    private boolean toggle(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "general.players-only");
            return true;
        }
        var holograms = plugin.holograms();
        if (holograms == null || !holograms.config().enabled()) {
            messages().send(player, "hologram.feature-disabled");
            return true;
        }
        if (!holograms.config().toggleCommandEnabled()) {
            messages().send(player, "hologram.command-disabled");
            return true;
        }
        List<Spawner> own = plugin.manager().ownedBy(player.getUniqueId());
        if (own.isEmpty()) {
            messages().send(player, "general.no-spawners");
            return true;
        }
        boolean state = !own.get(0).hologram();
        own.forEach(s -> {
            s.hologram(state);
            plugin.markDirty(s);
            plugin.holograms().update(s);
        });
        messages().send(player, state ? "hologram.toggled-on" : "hologram.toggled-off");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        switch (canonical(command.getName())) {
            case "bspawner" -> {
                if (!sender.hasPermission("bravenspawner.admin")) return List.of();
                if (args.length == 1) {
                    out.addAll(List.of("help", "give", "list", "doctor", "reload", "booster", "boosteritem", "sellwand", "modifier", "menu", "corrupt", "repair"));
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
