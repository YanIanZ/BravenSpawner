package dev.yanianz.bravenspawner.listener;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.mechanics.HopperLink;
import dev.yanianz.bravenspawner.mechanics.Mending;
import dev.yanianz.bravenspawner.mechanics.Ninja;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Block lifecycle: place, break, stack, convert, interact.
 *
 * <p>Every handler runs at HIGH and honours cancellation so protection plugins keep
 * the final word. Breaking respects silk touch, durability and the collection
 * switch; stacking respects the per-chunk limit and the stack cap.
 */
public final class BlockListener implements Listener {

    private final BravenSpawner plugin;
    private final Ninja ninja;
    private final HopperLink hopperLink;

    public BlockListener(BravenSpawner plugin) {
        this.plugin = plugin;
        this.ninja = new Ninja(plugin);
        this.hopperLink = new HopperLink(plugin);
        this.ninja.start();
        this.hopperLink.start();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemInHand();
        Settings s = plugin.settings();

        String product = item.getItemMeta() == null ? null
                : item.getItemMeta().getPersistentDataContainer().get(plugin.itemProductKey(), PersistentDataType.STRING);
        String kindRaw = item.getItemMeta() == null ? null
                : item.getItemMeta().getPersistentDataContainer().get(plugin.itemKindKey(), PersistentDataType.STRING);

        if (product == null || kindRaw == null) {
            if (s.convertSpawners && event.getBlockPlaced().getType() == Material.SPAWNER) {
                convertVanilla(event, player);
            }
            return;
        }

        Block block = event.getBlockPlaced();
        if (s.isBlacklisted(block.getWorld().getName())) {
            player.sendMessage(Text.of("&cSpawners are disabled in this world."));
            event.setCancelled(true);
            return;
        }

        int chunkX = block.getX() >> 4;
        int chunkZ = block.getZ() >> 4;
        if (s.spawnerLimitInChunk > 0) {
            int total = plugin.manager().stackedInChunk(block.getWorld(), chunkX, chunkZ);
            if (total + 1 > s.spawnerLimitInChunk) {
                player.sendMessage(Text.of("&cChunk spawner limit reached (" + s.spawnerLimitInChunk + ")."));
                event.setCancelled(true);
                return;
            }
        }

        Spawner spawner;
        Spawner existing = plugin.manager().at(block.getLocation());
        if (existing != null && player.isSneaking()) {
            // shift-right-click stacking is handled in onInteract; a place on top is a new spawner
            event.setCancelled(true);
            return;
        }

        spawner = new Spawner(
                UUID.randomUUID(), player.getUniqueId(),
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                Spawner.Kind.valueOf(kindRaw), product, 1, 0, true);

        if (!plugin.manager().register(spawner)) {
            player.sendMessage(Text.of("&cA spawner already occupies this block."));
            event.setCancelled(true);
            return;
        }
        plugin.holograms().update(spawner);
        if (s.enableLogger) {
            plugin.getLogger().info(String.format("%s placed %s at %s %d,%d,%d",
                    player.getName(), product, block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
        }
        player.sendMessage(Text.of("&aSpawner placed: &f" + product));
    }

    private void convertVanilla(BlockPlaceEvent event, Player player) {
        Block block = event.getBlockPlaced();
        String mob = "ZOMBIE";
        if (block.getState() instanceof CreatureSpawner cs && cs.getSpawnedType() != null) {
            mob = cs.getSpawnedType().name();
        }
        if (plugin.settings().mobs().get(mob) == null) mob = "ZOMBIE";
        Spawner spawner = new Spawner(UUID.randomUUID(), player.getUniqueId(),
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                Spawner.Kind.MOB, mob, 1, 0, true);
        plugin.manager().register(spawner);
        plugin.holograms().update(spawner);
        player.sendMessage(Text.of("&aConverted vanilla spawner: &f" + mob));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Spawner spawner = plugin.manager().at(event.getBlock().getLocation());
        if (spawner == null) return;

        Player player = event.getPlayer();
        Settings s = plugin.settings();
        boolean admin = player.hasPermission("bravenspawner.admin");

        if (!admin && !spawner.isOwner(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(Text.of("&cThat spawner is not yours."));
            return;
        }

        if (s.corruptionEnabled && spawner.corrupted() && !s.corruptionAllowBreaking && !admin) {
            event.setCancelled(true);
            player.sendMessage(Text.of("&cThis spawner is corrupted — repair it first."));
            return;
        }

        if (s.silkTouchOnly && !admin) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand == null || !hand.containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH)) {
                event.setCancelled(true);
                player.sendMessage(Text.of("&cSilk Touch is required to break spawners."));
                return;
            }
        }

        // Repair first, then apply the normal break cost, so a Mending tool gets
        // the spawner's banked XP instead of losing durability to this break.
        Mending.repair(plugin, player, spawner);

        if (s.removeDurabilityOnBreak && player.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand != null && hand.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable) {
                damageable.setDamage(damageable.getDamage() + 1);
                hand.setItemMeta(damageable);
            }
        }

        plugin.manager().unregister(spawner);
        plugin.holograms().remove(spawner.id());
        event.setDropItems(false);

        Location drop = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        spawner.contents().forEach(item -> event.getBlock().getWorld().dropItemNaturally(drop, item));
        if (s.blockCollectionInInventory) {
            ItemStack item = plugin.createSpawnerItem(spawner);
            var leftover = player.getInventory().addItem(item);
            leftover.values().forEach(l -> event.getBlock().getWorld().dropItemNaturally(drop, l));
        } else {
            event.getBlock().getWorld().dropItemNaturally(drop, plugin.createSpawnerItem(spawner));
        }
        player.sendMessage(Text.of("&eSpawner removed; contents dropped."));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Spawner spawner = plugin.manager().at(block.getLocation());
        if (spawner == null) return;

        Player player = event.getPlayer();
        Settings s = plugin.settings();
        event.setCancelled(true);

        if (plugin.manager().guiThrottled(player.getUniqueId(), s.cooldownGuiMs)) return;

        ItemStack hand = event.getItem();
        if (hand != null && s.enableBoosters) {
            Double multiplier = s.boosterItem(hand.getType());
            if (multiplier != null) {
                plugin.boosters().grantSpawner(spawner.id(), multiplier, s.boosterSeconds);
                hand.setAmount(hand.getAmount() - 1);
                player.sendMessage(Text.of("&aBooster &f" + multiplier + "x&a applied."));
                return;
            }
        }

        if (player.isSneaking()) {
            stack(player, spawner, event.getItem());
            return;
        }

        if (!spawner.canUse(player.getUniqueId()) && !player.hasPermission("bravenspawner.admin")) {
            player.sendMessage(Text.of("&cThat spawner is not yours."));
            return;
        }

        if (s.skipMenu) plugin.gui().openStorage(player, spawner, 0);
        else plugin.gui().openMenu(player, spawner);
    }

    private void stack(Player player, Spawner spawner, ItemStack item) {
        Settings s = plugin.settings();
        if (item == null || item.getType() != s.spawnerMaterial) return;
        if (item.getItemMeta() == null) return;
        String product = item.getItemMeta().getPersistentDataContainer()
                .get(plugin.itemProductKey(), PersistentDataType.STRING);
        if (product == null || !product.equals(spawner.product())) return;

        int incoming = s.fullStack ? item.getAmount() : Math.min(item.getAmount(), 64);
        int room = s.stackLimit - spawner.stack();
        if (room <= 0) {
            player.sendMessage(Text.of("&cThis spawner is fully stacked."));
            return;
        }
        int move = Math.min(incoming, room);
        spawner.addStack(move);
        item.setAmount(item.getAmount() - move);
        plugin.holograms().update(spawner);
        player.sendMessage(Text.of("&aSpawner now holds &f" + spawner.stack() + "&a."));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!plugin.settings().disableExplosionDamage) return;
        event.blockList().removeIf(b -> plugin.manager().at(b.getLocation()) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!plugin.settings().disableExplosionDamage) return;
        event.blockList().removeIf(b -> plugin.manager().at(b.getLocation()) != null);
    }

    /*
     * Ninja state is per connection. The client rebuilds chunks on these
     * transitions, so stale AIR overrides must be discarded instead of trusted.
     */

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ninja.forget(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ninja.forget(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        ninja.forget(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        ninja.forget(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        ninja.forget(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() != plugin) return;
        ninja.stop();
        hopperLink.stop();
    }
}
