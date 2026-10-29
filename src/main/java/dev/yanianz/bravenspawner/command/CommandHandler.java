package dev.yanianz.bravenspawner.command;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
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
            sender.sendMessage(Text.of("&e/bspawner give|list|reload|booster|menu|corrupt|repair"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(sender, args);
            case "list" -> list(sender);
            case "reload" -> {
                plugin.reloadAll();
                sender.sendMessage(Text.of("&aReloaded."));
            }
            case "booster" -> booster(sender, args);
            case "menu" -> {
                if (sender instanceof Player p) plugin.gui().openMenu(p, nearest(p));
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
        var leftover = target.getInventory().addItem(item);
        leftover.values().forEach(l -> target.getWorld().dropItemNaturally(target.getLocation(), l));
        target.sendMessage(Text.of("&aReceived &f" + product + " &ax" + stack));
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
        double multiplier;
        try {
            multiplier = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Text.of("&cInvalid multiplier."));
            return;
        }
        long seconds = plugin.settings().boosterSeconds();
        if (args.length > 3) {
            try {
                seconds = Long.parseLong(args[3]);
            } catch (NumberFormatException ignored) {
            }
        }
        if (target == null) {
            sender.sendMessage(Text.of("&cPlayer not found."));
            return;
        }
        plugin.boosters().grantPlayer(target.getUniqueId(), multiplier, seconds);
        sender.sendMessage(Text.of("&aBooster " + multiplier + "x for " + target.getName()));
    }

    private void corrupt(CommandSender sender) {
        if (!(sender instanceof Player player)) return;
        Spawner spawner = nearest(player);
        if (spawner == null) return;
        spawner.corrupt();
        player.sendMessage(Text.of("&cSpawner corrupted."));
    }

    private void repair(CommandSender sender) {
        if (!(sender instanceof Player player)) return;
        Spawner spawner = nearest(player);
        if (spawner == null) return;
        spawner.repair();
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
            plugin.holograms().update(s);
        });
        player.sendMessage(Text.of("&eHolograms: " + (state ? "on" : "off")));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("bspawner")) {
            if (args.length == 1) out.addAll(List.of("give", "list", "reload", "booster", "menu", "corrupt", "repair"));
            else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
                out.addAll(plugin.settings().mobs().keySet());
                plugin.settings().items().keySet().forEach(i -> out.add("item:" + i));
            } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
                Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            } else if (args.length == 2 && args[0].equalsIgnoreCase("booster")) {
                Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            }
        } else if (command.getName().equalsIgnoreCase("bmodify") && args.length == 1) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
