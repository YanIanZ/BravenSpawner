package dev.yanianz.bravenspawner.mechanics;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Schedulers;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side spawner invisibility ("ninja" mode).
 *
 * <p>A virtual spawner still owns a real block, so without packet work every player
 * would see it from across the render distance. This class sends a per-player AIR
 * block change for spawners beyond the start distance and re-sends the real block
 * data once the player comes close. The state is per player because every client
 * has its own view of the world. The task itself only reads the manager and
 * dispatches to the region that owns each player, which keeps the work legal on
 * Folia as well as Paper.
 */
public final class Ninja {

    /** Largest client view distance the protocol allows, in chunks. */
    private static final int MAX_VIEW_DISTANCE = 32;

    private final BravenSpawner plugin;

    /** player -> spawner id -> block location currently overridden to AIR. */
    private final Map<UUID, Map<UUID, Location>> hidden = new ConcurrentHashMap<>();

    private volatile ScheduledTask task;
    private volatile boolean running;

    public Ninja(BravenSpawner plugin) {
        this.plugin = plugin;
    }

    /** Starts the refresh loop; the loop itself is cheap when the feature is off. */
    public void start() {
        if (task != null) return;
        running = true;
        long seconds = Math.max(1L, plugin.getConfig().getLong("ninja-spawners.refresh-rate", 1L));
        long ticks = seconds * 20L;
        // One global timer drives all players. Each player's packet work is then
        // handed to the region scheduler so no client is touched off-region.
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> tick(), ticks, ticks);
    }

    /** Cancels the loop and gives every online player the real blocks back. */
    public void stop() {
        running = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        restoreAll();
    }

    /**
     * Drops the tracked view state for a player.
     *
     * <p>Called on join, quit, respawn, teleport and world change. The client
     * reloads chunks around those transitions, so the next refresh starts from
     * "visible" and re-applies the correct state instead of trusting stale AIR.
     */
    public void forget(Player player) {
        if (player != null) hidden.remove(player.getUniqueId());
    }

    void tick() {
        if (!running || !plugin.isEnabled() || !plugin.ready()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Schedulers.onEntity(plugin, player, () -> refresh(player),
                    () -> hidden.remove(player.getUniqueId()));
        }
    }

    private void refresh(Player player) {
        if (!running || !plugin.isEnabled() || !plugin.ready()) return;
        if (player == null || !player.isOnline()) {
            if (player != null) hidden.remove(player.getUniqueId());
            return;
        }

        if (!plugin.getConfig().getBoolean("ninja-spawners.enable-invisibility", false)) {
            restore(player);
            return;
        }

        int startDistance = Math.max(0, plugin.getConfig()
                .getInt("ninja-spawners.start-invisibility-distance", 128));
        int visibilityDistance = Math.max(0, plugin.getConfig()
                .getInt("ninja-spawners.visibility-distance", 15));
        double startSq = (double) startDistance * startDistance;
        double visibilitySq = (double) visibilityDistance * visibilityDistance;

        String worldName = player.getWorld().getName();
        Map<UUID, Location> state = hidden.computeIfAbsent(
                player.getUniqueId(), ignored -> new ConcurrentHashMap<>());

        // The client holds no chunks beyond its view distance, so a block change
        // there is discarded. Only spawners within view distance (plus one chunk
        // of margin) are candidates; the chunk index keeps this independent of the
        // total spawner count and avoids re-sending AIR for every distant block.
        int radius = viewDistance(player) + 1;
        int chunkX = (int) Math.floor(player.getX()) >> 4;
        int chunkZ = (int) Math.floor(player.getZ()) >> 4;
        for (Spawner spawner : plugin.manager().nearChunk(worldName, chunkX, chunkZ, radius)) {
            if (spawner == null) continue;
            Location block = spawner.blockLocation();
            if (block == null) continue;

            double distanceSq = distanceSquared(player, block);
            if (distanceSq <= visibilitySq) {
                if (state.remove(spawner.id()) != null) show(player, block);
            } else if (distanceSq > startSq) {
                // Repeat the override so a client chunk reload does not reveal a
                // still-distant spawner. No foreign block/chunk read is needed.
                player.sendBlockChange(block, Material.AIR.createBlockData());
                state.put(spawner.id(), block.clone());
            }
        }

        prune(player, state);
    }

    /** Server send view distance in chunks, falling back to the protocol maximum. */
    static int viewDistance(Player player) {
        int distance = player.getSendViewDistance();
        if (distance <= 0) distance = player.getViewDistance();
        return distance <= 0 ? MAX_VIEW_DISTANCE : Math.min(distance, MAX_VIEW_DISTANCE);
    }

    /** Restores spawners that were removed while a player still had them hidden. */
    private void prune(Player player, Map<UUID, Location> state) {
        Iterator<Map.Entry<UUID, Location>> iterator = state.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Location> entry = iterator.next();
            if (plugin.manager().byId(entry.getKey()) != null) continue;
            show(player, entry.getValue());
            iterator.remove();
        }
        if (state.isEmpty()) hidden.remove(player.getUniqueId(), state);
    }

    private void restore(Player player) {
        Map<UUID, Location> state = hidden.remove(player.getUniqueId());
        if (state == null) return;
        for (Location location : state.values()) show(player, location);
    }

    private void restoreAll() {
        for (Player player : Bukkit.getOnlinePlayers()) restore(player);
        hidden.clear();
    }

    private void show(Player player, Location location) {
        if (location == null || location.getWorld() == null) return;
        try {
            // The block can be in a different Folia region from the player.
            // Capture block data there, then send the packet on the entity owner.
            Schedulers.at(plugin, location, () -> {
                if (!isLoaded(location)) return;
                var data = location.getBlock().getBlockData().clone();
                try {
                    Schedulers.onEntity(plugin, player, () -> {
                        if (!player.isOnline() || !player.getWorld().equals(location.getWorld())) return;
                        Map<UUID, Location> overrides = hidden.get(player.getUniqueId());
                        if (overrides != null && overrides.containsValue(location)) return;
                        player.sendBlockChange(location, data);
                    }, () -> hidden.remove(player.getUniqueId()));
                } catch (RuntimeException e) {
                    logRestoreFailure(e);
                }
            });
        } catch (RuntimeException e) {
            logRestoreFailure(e);
        }
    }

    private void logRestoreFailure(RuntimeException exception) {
        // Folia may reject plugin tasks during disable; never fall back to a
        // foreign-region block read or player packet. Reconnecting clears AIR.
        if (plugin.isEnabled()) plugin.getLogger().warning("could not restore ninja block view: "
                + exception.getMessage());
    }

    private boolean isLoaded(Location location) {
        World world = location.getWorld();
        return world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private double distanceSquared(Player player, Location block) {
        double dx = player.getX() - (block.getBlockX() + 0.5);
        double dy = player.getY() - (block.getBlockY() + 0.5);
        double dz = player.getZ() - (block.getBlockZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }
}
