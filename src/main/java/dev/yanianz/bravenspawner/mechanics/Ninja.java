package dev.yanianz.bravenspawner.mechanics;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
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

    private final BravenSpawner plugin;

    /** player -> spawner id -> block location currently overridden to AIR. */
    private final Map<UUID, Map<UUID, Location>> hidden = new ConcurrentHashMap<>();

    private ScheduledTask task;

    public Ninja(BravenSpawner plugin) {
        this.plugin = plugin;
    }

    /** Starts the refresh loop; the loop itself is cheap when the feature is off. */
    public void start() {
        if (task != null) return;
        long seconds = Math.max(1L, plugin.getConfig().getLong("ninja-spawners.refresh-rate", 1L));
        long ticks = seconds * 20L;
        // One global timer drives all players. Each player's packet work is then
        // handed to the region scheduler so no client is touched off-region.
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> tick(), ticks, ticks);
    }

    /** Cancels the loop and gives every online player the real blocks back. */
    public void stop() {
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

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location location = player.getLocation();
            if (location.getWorld() == null) continue;
            Bukkit.getRegionScheduler().execute(plugin, location, () -> refresh(player));
        }
    }

    private void refresh(Player player) {
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
        Set<UUID> present = new HashSet<>();

        for (Spawner spawner : plugin.manager().all()) {
            if (spawner == null || !worldName.equals(spawner.world())) continue;
            Location block = spawner.blockLocation();
            if (block == null) continue;
            present.add(spawner.id());

            // An unloaded chunk has no client-side block to override. Clearing
            // the entry lets the next refresh decide again after it loads.
            if (!isLoaded(block)) {
                state.remove(spawner.id());
                continue;
            }

            double distanceSq = distanceSquared(player, block);
            if (distanceSq <= visibilitySq) {
                if (state.remove(spawner.id()) != null) show(player, block);
            } else if (distanceSq > startSq && !state.containsKey(spawner.id())) {
                player.sendBlockChange(block, Material.AIR.createBlockData());
                state.put(spawner.id(), block.clone());
            }
        }

        prune(player, state, present);
    }

    /** Restores spawners that were removed while a player still had them hidden. */
    private void prune(Player player, Map<UUID, Location> state, Set<UUID> present) {
        Iterator<Map.Entry<UUID, Location>> iterator = state.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Location> entry = iterator.next();
            if (present.contains(entry.getKey())) continue;
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
        if (location == null || location.getWorld() == null || !isLoaded(location)) return;
        // A world change can race the refresh task; a block packet has no world
        // id, so never send one for a world the player is no longer in.
        if (!player.getWorld().equals(location.getWorld())) return;
        Block block = location.getBlock();
        player.sendBlockChange(location, block.getBlockData());
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
