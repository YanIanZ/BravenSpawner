package dev.yanianz.bravenspawner.listener;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.manager.StackService;
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

import java.util.UUID;

/**
 * Block lifecycle: place, break, stack, convert, interact.
 *
 * <p>Every handler runs at HIGH and honours cancellation so protection plugins keep
 * the final word. Breaking respects silk touch, durability and the collection
 * switch; stacking is delegated to {@link StackService}, which owns authorization,
 * item identity and both caps.
 *
 * <p>Spawner items carry their virtual count in the persistent data container and
 * keep a physical amount of 1. Place, merge and break all read and write that one
 * field, so a count survives every transfer without drift.
 */
public final class BlockListener implements Listener {

    private final BravenSpawner plugin;
    private final Ninja ninja;
    private final HopperLink hopperLink;
    private final StackService stackService;
    private final ActionCooldowns breakCooldowns = new ActionCooldowns();
    private final ActionCooldowns stackCooldowns = new ActionCooldowns();

    public BlockListener(BravenSpawner plugin) {
        this.plugin = plugin;
        this.ninja = new Ninja(plugin);
        this.hopperLink = new HopperLink(plugin);
        this.stackService = new StackService(plugin);
        this.ninja.start();
        this.hopperLink.start();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemInHand();
        Settings s = plugin.settings();

        Spawner.Kind kind = StackService.kindOf(plugin, item);
        String product = StackService.productOf(plugin, item);

        if (!plugin.ready() && (kind != null || isSpawnerBlock(event.getBlockPlaced()))) {
            event.setCancelled(true);
            player.sendMessage(Text.of(unavailableMessage()));
            return;
        }
        if (kind == null || product == null) {
            if (s.convertSpawners && event.getBlockPlaced().getType() == Material.SPAWNER) {
                convertVanilla(event, player);
            }
            return;
        }

        var definition = kind == Spawner.Kind.MOB ? plugin.products().mob(product) : plugin.products().item(product);
        if (definition == null || kind == Spawner.Kind.ITEM && !s.useItemSpawners) {
            event.setCancelled(true);
            player.sendMessage(Text.of("&cThat spawner product is unavailable."));
            return;
        }
        Block block = event.getBlockPlaced();
        if (plugin.protection() != null && !plugin.protection().canPlace(player, block.getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (s.isBlacklisted(block.getWorld().getName())) {
            player.sendMessage(Text.of("&cSpawners are disabled in this world."));
            event.setCancelled(true);
            return;
        }

        // The virtual count is the canonical value; the physical amount is always
        // one. Clamping keeps a hand-edited item inside the configured cap.
        int stack = StackService.canonicalCount(plugin, item, s.stackLimit);
        int productLimit = definition.maxStack() <= 0 ? s.stackLimit : Math.min(s.stackLimit, definition.maxStack());
        if (stack > productLimit) {
            event.setCancelled(true);
            player.sendMessage(Text.of("&cThis product's stack limit is " + productLimit + "."));
            return;
        }

        if (!plugin.manager().chunkAllowsNewSpawner(block.getWorld(), block.getX() >> 4, block.getZ() >> 4, stack)) {
            player.sendMessage(Text.of("&cChunk spawner limit reached (" + s.spawnerLimitInChunk + ")."));
            event.setCancelled(true);
            return;
        }

        Spawner existing = plugin.manager().at(block.getLocation());
        if (existing != null && player.isSneaking()) {
            // shift-right-click stacking is handled in onInteract; a place on top is a new spawner
            event.setCancelled(true);
            return;
        }

        Spawner spawner = new Spawner(
                UUID.randomUUID(), player.getUniqueId(),
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                kind, product, stack, 0, true);

        if (!plugin.manager().register(spawner)) {
            player.sendMessage(Text.of("&cA spawner already occupies this block."));
            event.setCancelled(true);
            return;
        }
        plugin.holograms().update(spawner);
        plugin.record(spawner, dev.yanianz.bravenspawner.audit.AuditEvent.Action.PLACE, player, stack, "placed");
        if (s.enableLogger) {
            plugin.getLogger().info(String.format("%s placed %s x%d at %s %d,%d,%d",
                    player.getName(), product, spawner.stack(),
                    block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
        }
        player.sendMessage(Text.of("&aSpawner placed: &f" + product
                + (spawner.stack() > 1 ? " &7x" + spawner.stack() : "")));
    }

    /**
     * Turns a placed vanilla spawner into a managed one. Blacklisted worlds and mob
     * types without a product stay vanilla (never silently become ZOMBIE), and the
     * chunk cap applies exactly as for a spawner item.
     */
    private void convertVanilla(BlockPlaceEvent event, Player player) {
        if (!plugin.ready()) { event.setCancelled(true); return; }
        Block block = event.getBlockPlaced();
        if (plugin.settings().isBlacklisted(block.getWorld().getName())) return;
        String mob = vanillaProduct(block);
        if (mob == null) return;
        if (!plugin.manager().chunkAllowsNewSpawner(block.getWorld(), block.getX() >> 4, block.getZ() >> 4, 1)) {
            player.sendMessage(Text.of("&cChunk spawner limit reached (" + plugin.settings().spawnerLimitInChunk + ")."));
            event.setCancelled(true);
            return;
        }
        Spawner spawner = new Spawner(UUID.randomUUID(), player.getUniqueId(),
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                Spawner.Kind.MOB, mob, 1, 0, true);
        if (!plugin.manager().register(spawner)) return;
        plugin.holograms().update(spawner);
        player.sendMessage(Text.of("&aConverted vanilla spawner: &f" + mob));
    }

    /** Product id for the vanilla spawner's mob, or null when it has none or no product exists. */
    private String vanillaProduct(Block block) {
        if (!(block.getState() instanceof CreatureSpawner cs) || cs.getSpawnedType() == null) return null;
        String mob = cs.getSpawnedType().name();
        if (plugin.products().mob(mob) != null) return mob;
        return plugin.settings().mob(mob) != null ? mob : null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.ready()) {
            // Until the stored records are loaded (or when storage could not be
            // opened at all) a managed spawner looks like a vanilla block; breaking
            // it would destroy the record's stack, loot and XP.
            if (isSpawnerBlock(event.getBlock())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(Text.of(unavailableMessage()));
            }
            return;
        }
        Spawner spawner = plugin.manager().at(event.getBlock().getLocation());
        if (spawner == null) return;
        // Banked XP is paid out explicitly below; the vanilla spawner orb drop
        // (15-43 XP) would otherwise be farmable by placing and breaking.
        event.setExpToDrop(0);

        Player player = event.getPlayer();
        Settings s = plugin.settings();
        boolean admin = player.hasPermission("bravenspawner.admin");

        if (plugin.protection() != null && !plugin.protection().canBreak(player, event.getBlock().getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (!plugin.mayBreak(player, spawner)) {
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

        if (!plugin.ready() || spawner.storedCount() > 0) {
            event.setCancelled(true);
            player.sendMessage(Text.of("&cCollect the stored items before breaking this spawner."));
            return;
        }
        long wait = breakCooldowns.acquire(player.getUniqueId(), s.cooldownBreakMs, System.currentTimeMillis());
        if (wait > 0) {
            event.setCancelled(true);
            player.sendMessage(Text.of("&cWait &f" + seconds(wait) + "s &cbefore breaking another spawner."));
            return;
        }

        // Repair first, then apply the normal break cost, so a Mending tool gets
        // the spawner's banked XP instead of losing durability to this break.
        Mending.repair(plugin, player, spawner);
        int remainingXp = spawner.takeXp();
        if (remainingXp > 0) player.giveExp(remainingXp);

        if (s.removeDurabilityOnBreak && player.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand != null && hand.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable) {
                damageable.setDamage(damageable.getDamage() + 1);
                hand.setItemMeta(damageable);
            }
        }

        plugin.record(spawner, dev.yanianz.bravenspawner.audit.AuditEvent.Action.BREAK, player, spawner.stack(), "broken");
        plugin.manager().unregister(spawner);
        plugin.holograms().remove(spawner.id());
        event.setDropItems(false);

        Location drop = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        spawner.contents().forEach(item -> event.getBlock().getWorld().dropItemNaturally(drop, item));
        // createSpawnerItem stamps the model's virtual count into the PDC, which is
        // the same field place and merge read back.
        if (s.blockCollectionInInventory) {
            ItemStack item = plugin.createSpawnerItem(spawner);
            var leftover = player.getInventory().addItem(item);
            leftover.values().forEach(l -> event.getBlock().getWorld().dropItemNaturally(drop, l));
        } else {
            event.getBlock().getWorld().dropItemNaturally(drop, plugin.createSpawnerItem(spawner));
        }
        player.sendMessage(Text.of("&eSpawner removed; contents dropped."));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND) return;
        if (plugin.boosters() != null && plugin.boosters().isBooster(event.getItem())) return;
        if (plugin.wands() != null && plugin.wands().isWand(event.getItem())) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        if (!plugin.ready()) {
            if (isSpawnerBlock(block)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(Text.of(unavailableMessage()));
            }
            return;
        }
        Spawner spawner = plugin.manager().at(block.getLocation());
        if (spawner == null) return;

        Player player = event.getPlayer();
        Settings s = plugin.settings();
        if (!plugin.mayUse(player, spawner)) return;
        if (plugin.restrictions() != null && !plugin.restrictions().canOpen(player, spawner)) return;
        if (plugin.protection() != null && !plugin.protection().canUse(player, block.getLocation())) return;
        event.setCancelled(true);

        if (plugin.manager().guiThrottled(player.getUniqueId(), s.cooldownGuiMs)) return;

        if (player.isSneaking()) {
            long wait = stackCooldowns.acquire(player.getUniqueId(), s.cooldownStackMs, System.currentTimeMillis());
            if (wait > 0) {
                player.sendMessage(Text.of("&cWait &f" + seconds(wait) + "s &cbefore stacking again."));
                return;
            }
            report(player, stackService.stack(player, spawner, event.getItem()), spawner);
            return;
        }

        if (!plugin.mayUse(player, spawner)) {
            player.sendMessage(Text.of("&cThat spawner is not yours."));
            return;
        }

        if (s.skipMenu) plugin.gui().openStorage(player, spawner, 0);
        else plugin.gui().openMenu(player, spawner);
    }

    /** Turns a merge result into player-facing text; the model is untouched on failure. */
    private void report(Player player, StackService.Result result, Spawner spawner) {
        if (result == null) return;
        switch (result.status()) {
            case OK -> {
                plugin.holograms().update(spawner);
                if (plugin.particles() != null) plugin.particles().burst(dev.yanianz.bravenspawner.particles.SpawnerParticles.Effect.STACK, spawner.location());
                player.sendMessage(Text.of("&aSpawner now holds &f" + result.stackAfter() + "&a."));
            }
            case NOT_AUTHORIZED -> player.sendMessage(Text.of("&cThat spawner is not yours."));
            case NO_ITEM -> player.sendMessage(Text.of("&7Hold a spawner item to merge it."));
            case WRONG_ITEM -> player.sendMessage(Text.of("&cOnly an identical spawner item can be merged."));
            case CHUNK_LIMIT -> player.sendMessage(Text.of("&cChunk spawner limit reached ("
                    + plugin.settings().spawnerLimitInChunk + ")."));
            case STACK_LIMIT -> player.sendMessage(Text.of("&cThis spawner can hold &f"
                    + result.room() + "&c more."));
            default -> player.sendMessage(Text.of("&cThat merge is not possible."));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        shieldFromExplosion(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        shieldFromExplosion(event.blockList());
    }

    private void shieldFromExplosion(java.util.List<Block> blocks) {
        if (!plugin.ready()) {
            // Records are not loaded: any spawner block may be a managed one.
            blocks.removeIf(BlockListener::isSpawnerBlock);
            return;
        }
        if (!plugin.settings().disableExplosionDamage) return;
        blocks.removeIf(b -> plugin.manager().at(b.getLocation()) != null);
    }

    static boolean isSpawnerBlock(Block block) {
        return block != null && block.getType() == Material.SPAWNER;
    }

    private String unavailableMessage() {
        return plugin.isStorageAvailable()
                ? "&cSpawner storage is still loading."
                : "&cSpawner storage is unavailable; spawners are locked until an administrator fixes it.";
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
        breakCooldowns.forget(event.getPlayer().getUniqueId());
        stackCooldowns.forget(event.getPlayer().getUniqueId());
    }

    private static String seconds(long millis) {
        return String.format(java.util.Locale.ROOT, "%.1f", Math.max(0.1, millis / 1000.0));
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
