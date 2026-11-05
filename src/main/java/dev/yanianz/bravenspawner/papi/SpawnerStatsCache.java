package dev.yanianz.bravenspawner.papi;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Immutable per-island spawner counts for PlaceholderAPI.
 *
 * <p>A placeholder request can arrive from any thread (chat, scoreboard, tab,
 * hologram, another plugin's async task), so it must not read world or block state
 * and must never wait. This class is the only thing a request touches: a
 * {@code volatile} map of small immutable records, rebuilt every
 * {@link #REFRESH_SECONDS} seconds on the async scheduler from the plugin's own
 * in-memory registry. A refresh publishes one complete map, so a request sees
 * either the previous or the next counts, never a half-built set.
 *
 * <p><b>Total</b> is the number of spawner records registered for the island.
 * <b>Active</b> is a record the plugin would still produce from:
 * <ol>
 *   <li>registered in {@link SpawnerManager} (the live instance, not a stale copy),</li>
 *   <li>not corrupted ({@link Spawner#corrupted()} is the corruption mechanic's own flag),</li>
 *   <li>stack above zero ({@link Spawner#stack()}),</li>
 *   <li>its product definition still resolves ({@code BravenSpawner#product}) — a
 *       deleted or renamed product file makes the record unable to produce, and</li>
 *   <li>the last production pass did not report a missing block
 *       ({@link SpawnerManager#blockMissing}) — that flag is set by the region pass
 *       when the world block is no longer a spawner and cleared when it is again.</li>
 * </ol>
 * <b>Broken</b> is {@code total - active}. Chunk/world load state is deliberately
 * not consulted here: island worlds unload when idle on this server, and asking for
 * it off the owning region thread is exactly what this cache exists to avoid. An
 * unloaded spawner keeps its last verdict until it produces again.
 */
public final class SpawnerStatsCache {

    /** Refresh cadence; placeholder reads only ever see the last snapshot. */
    public static final long REFRESH_SECONDS = 30L;

    /** SourbyCraft Aurora World Fabric names an island's own worlds {@code ssb_<uuid>_<dimension>}. */
    private static final String AWF_ISLAND_WORLD_PREFIX = "ssb_";

    /** Counts for one island; {@code active} can never exceed {@code total}. */
    public record Stats(int total, int active) {

        public static final Stats EMPTY = new Stats(0, 0);

        public Stats {
            total = Math.max(0, total);
            active = Math.max(0, Math.min(active, total));
        }

        public int broken() { return total - active; }
    }

    private final BravenSpawner plugin;
    /** SSB2 island of an owner UUID, or {@code null} when SSB2 is not installed. */
    private final Function<UUID, String> ownerIsland;

    private volatile Map<String, Stats> byIsland = Map.of();
    /** Guarded by {@code this}. */
    private ScheduledTask task;

    public SpawnerStatsCache(BravenSpawner plugin, Function<UUID, String> ownerIsland) {
        this.plugin = plugin;
        this.ownerIsland = ownerIsland;
    }

    /** Starts the 30 s refresh on the async scheduler; a no-op when already started. */
    public synchronized void start() {
        if (task != null) return;
        task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, ignored -> refresh(),
                REFRESH_SECONDS, REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (task == null) return;
        task.cancel();
        task = null;
    }

    /**
     * The island UUID string for {@code player}, or {@code null} when they have
     * none. In-memory read; any thread may call it.
     */
    public String islandOf(UUID player) {
        return ownerIsland == null ? null : ownerIsland.apply(player);
    }

    /** Counts for one island id; {@link Stats#EMPTY} when the island has no spawners. */
    public Stats statsFor(String islandId) {
        return islandId == null ? Stats.EMPTY : byIsland.getOrDefault(islandId, Stats.EMPTY);
    }

    /** Rebuilds the snapshot; runs on the async scheduler and must never throw. */
    public void refresh() {
        try {
            SpawnerManager manager = plugin.manager();
            if (manager == null) {
                byIsland = Map.of();
                return;
            }
            Map<String, int[]> counts = new HashMap<>();
            for (Spawner spawner : manager.all()) {
                if (spawner == null) continue;
                String island = islandOf(spawner);
                if (island == null) continue;
                int[] slot = counts.computeIfAbsent(island, key -> new int[2]);
                slot[0]++;
                if (active(manager, spawner)) slot[1]++;
            }
            Map<String, Stats> next = new HashMap<>(counts.size());
            counts.forEach((island, slot) -> next.put(island, new Stats(slot[0], slot[1])));
            byIsland = Map.copyOf(next);
        } catch (RuntimeException failure) {
            // Keep the previous snapshot: stale counts beat a broken placeholder.
            plugin.getLogger().warning("spawner placeholder snapshot failed: " + failure);
        }
    }

    /** The active definition; see the class comment. */
    private boolean active(SpawnerManager manager, Spawner spawner) {
        if (spawner.corrupted()) return false;
        if (spawner.stack() <= 0) return false;
        if (plugin.product(spawner) == null) return false;
        return !manager.blockMissing(spawner);
    }

    /** The island a record belongs to, or {@code null} when it cannot be attributed. */
    private String islandOf(Spawner spawner) {
        // An island's own world identifies it without any API call or world access,
        // which keeps the refresh safe even while SSB2 is reloading.
        String fromWorld = islandFromWorld(spawner.world());
        if (fromWorld != null) return fromWorld;
        if (ownerIsland == null || spawner.owner() == null) return null;
        try {
            return ownerIsland.apply(spawner.owner());
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /**
     * UUID of the island owning an {@code ssb_<32 hex>_<dimension>} world, or null.
     *
     * <p>Same naming rule {@code hook.IslandDisbandCleanup} uses for disband cleanup,
     * so both agree on which island a spawner world belongs to.
     */
    static String islandFromWorld(String world) {
        if (world == null || !world.startsWith(AWF_ISLAND_WORLD_PREFIX)) return null;
        int start = AWF_ISLAND_WORLD_PREFIX.length();
        int end = world.indexOf('_', start);
        if (end < 0) end = world.length();
        String hex = world.substring(start, end);
        if (hex.length() != 32) return null;
        try {
            long most = Long.parseUnsignedLong(hex.substring(0, 16), 16);
            long least = Long.parseUnsignedLong(hex.substring(16), 16);
            return new UUID(most, least).toString();
        } catch (NumberFormatException notAnIslandWorld) {
            return null;
        }
    }
}
