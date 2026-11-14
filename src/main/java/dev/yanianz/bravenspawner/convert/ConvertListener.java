package dev.yanianz.bravenspawner.convert;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.util.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Automatic conversion triggers: chunk loads and player joins.
 *
 * <p>Chunk loads convert vanilla spawner blocks; joins swap spawner items. Both
 * handlers only filter cheaply on the event thread and then hand the real work
 * to the owning scheduler: the chunk's region for blocks, the player's entity
 * thread for inventories. While the plugin is not ready everything is skipped
 * fail-closed — a chunk is seen again on its next load, and a join scan simply
 * waits for the next join — and no unbounded work is ever queued.
 */
public final class ConvertListener implements Listener {

    private final BravenSpawner plugin;

    public ConvertListener(BravenSpawner plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.ready() || plugin.convert() == null) {
            return;
        }
        Settings settings = plugin.settings();
        if (settings == null || !settings.convertEnabled || !settings.convertChunkLoad) {
            return;
        }
        Chunk chunk = event.getChunk();
        if (chunk == null) {
            return;
        }
        World world = chunk.getWorld();
        if (world == null || settings.isBlacklisted(world.getName())) {
            return;
        }
        Location anchor = new Location(world, (chunk.getX() << 4) + 8,
                Math.max(world.getMinHeight(), 0), (chunk.getZ() << 4) + 8);
        boolean owned;
        try {
            owned = Bukkit.isOwnedByCurrentRegion(anchor);
        } catch (RuntimeException unknown) {
            owned = false;
        }
        if (owned) {
            plugin.convert().convertChunkWithRetry(chunk, 0);
            return;
        }
        try {
            Schedulers.at(plugin, anchor, () -> plugin.convert().convertChunkWithRetry(chunk, 0));
        } catch (RuntimeException rejected) {
            plugin.debug("convert: no region for " + world.getName() + " " + chunk.getX() + "," + chunk.getZ());
        }
    }

    /**
     * One inventory pass per join, on the player's entity thread.
     *
     * <p>Join (not first movement) is the trigger: the inventory is fully
     * loaded by join time, it fires exactly once per session, and a movement
     * trigger would either re-scan wastefully or need extra once-per-session
     * bookkeeping for no benefit.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.ready() || plugin.convert() == null) {
            return;
        }
        Settings settings = plugin.settings();
        if (settings == null || !settings.convertEnabled || !settings.convertJoinScan) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        try {
            Schedulers.onEntity(plugin, player, () -> plugin.convert().joinScan(player), () -> {
            });
        } catch (RuntimeException rejected) {
            plugin.debug("convert: no entity thread for " + player.getName());
        }
    }
}
