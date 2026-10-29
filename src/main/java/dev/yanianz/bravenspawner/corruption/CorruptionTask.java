package dev.yanianz.bravenspawner.corruption;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.holo.Holograms;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Text;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives the {@code corruption} section of config.yml.
 *
 * <p>The persisted {@code corrupted} flag on {@link Spawner} is the durable truth;
 * the countdown is deliberately memory-only. Reconstructing wall-clock deadlines
 * from a row that may have been written hours ago would corrupt every spawner the
 * instant a server came back up, so a restart re-arms healthy spawners instead.
 *
 * <p>The sweep runs on the global region scheduler once per second. It only flips
 * a volatile model flag — never a block or an entity — so it cannot race a region
 * thread; hologram refreshes are handed to the region that owns the spawner.
 */
public final class CorruptionTask {

    private static final long SWEEP_PERIOD_TICKS = 20L;

    private final BravenSpawner plugin;
    private final ConcurrentHashMap<UUID, Long> countdowns = new ConcurrentHashMap<>();

    private volatile ScheduledTask task;

    public CorruptionTask(BravenSpawner plugin) {
        this.plugin = plugin;
    }

    /** Starts the one-second sweep; repeated calls are ignored while it is live. */
    public void start() {
        if (!plugin.isEnabled()) return;
        ScheduledTask current = task;
        if (current != null && !current.isCancelled()) return;
        task = Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, scheduled -> sweep(), SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS);
    }

    /** Cancels the sweep and discards every pending countdown. */
    public void stop() {
        ScheduledTask current = task;
        task = null;
        if (current != null) {
            try {
                current.cancel();
            } catch (RuntimeException ignored) {
                // the scheduler may already have retired the task during shutdown
            }
        }
        countdowns.clear();
    }

    /** True when {@code corruption.enabled} is set in config.yml. */
    public boolean enabled() {
        return plugin.getConfig().getBoolean("corruption.enabled", false);
    }

    /**
     * Repairs one spawner and every other corrupted spawner within
     * {@code corruption.solve-radius} blocks on the same world.
     *
     * <p>Returns the number of spawners repaired, or 0 when the target was not
     * corrupted. The player may be null when this is called from a console
     * command; feedback is simply skipped then.
     */
    public int repair(Player player, Spawner spawner) {
        if (spawner == null) return 0;
        if (!spawner.corrupted()) {
            if (player != null) player.sendMessage(Text.of("&7That spawner is not corrupted."));
            return 0;
        }
        int repaired = repairAllNearby(spawner);
        if (player != null) {
            if (repaired > 1) {
                player.sendMessage(Text.of("&aRepaired &f" + repaired + "&a corrupted spawners."));
            } else {
                player.sendMessage(Text.of("&aCorrupted spawner repaired."));
            }
        }
        return repaired;
    }

    /**
     * Repairs {@code origin} plus its corrupted neighbours without player feedback.
     * Safe from any thread: only the model flag is touched, and the hologram update
     * is scheduled onto the spawner's own region.
     */
    public int repairAllNearby(Spawner origin) {
        if (origin == null) return 0;

        int radius = Math.max(0, plugin.getConfig().getInt("corruption.solve-radius", 5));
        double maxSquared = (double) radius * radius;
        int repaired = 0;

        SpawnerManager manager = plugin.manager();
        if (manager != null) {
            for (Spawner candidate : manager.all()) {
                if (candidate == null || !candidate.corrupted()) continue;
                if (!candidate.world().equals(origin.world())) continue;

                long dx = (long) candidate.x() - origin.x();
                long dy = (long) candidate.y() - origin.y();
                long dz = (long) candidate.z() - origin.z();
                if ((double) (dx * dx + dy * dy + dz * dz) > maxSquared) continue;

                repairOne(candidate);
                repaired++;
            }
        }

        // The origin can be unregistered or already handled by the loop above;
        // repairing it here keeps the contract true in both cases.
        if (origin.corrupted()) {
            repairOne(origin);
            repaired++;
        }
        return repaired;
    }

    private void sweep() {
        SpawnerManager manager = plugin.manager();
        if (!plugin.isEnabled() || manager == null) return;

        if (!enabled()) {
            if (!countdowns.isEmpty()) countdowns.clear();
            return;
        }

        long now = System.currentTimeMillis();
        long minMs = delayMs("corruption.delay.min", 3600L);
        long maxMs = delayMs("corruption.delay.max", 21600L);

        for (Spawner spawner : manager.all()) {
            if (spawner == null) continue;

            if (spawner.corrupted()) {
                countdowns.remove(spawner.id());
                continue;
            }

            long deadline = countdowns.computeIfAbsent(spawner.id(),
                    ignored -> deadline(now, randomDelayMs(minMs, maxMs)));
            if (now <= deadline) continue;

            // remove(key, value) makes the expiry single-shot even if a repair
            // clears the entry between the read above and this line.
            if (!countdowns.remove(spawner.id(), deadline)) continue;

            spawner.corrupt();
            Holograms holograms = plugin.holograms();
            if (holograms != null) holograms.update(spawner);
            if (plugin.getConfig().getBoolean("enable-logger", false)) {
                plugin.getLogger().info("Spawner " + spawner.id() + " corrupted at "
                        + spawner.world() + " " + spawner.x() + "," + spawner.y() + ","
                        + spawner.z());
            }
        }

        countdowns.keySet().removeIf(id -> manager.byId(id) == null);
    }

    private void repairOne(Spawner spawner) {
        spawner.repair();
        // The next sweep arms a fresh delay; repairing must not immediately re-corrupt.
        countdowns.remove(spawner.id());
        Holograms holograms = plugin.holograms();
        if (holograms != null) holograms.update(spawner);
    }

    private long delayMs(String path, long fallbackSeconds) {
        long seconds = Math.max(0L, plugin.getConfig().getLong(path, fallbackSeconds));
        return seconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : seconds * 1000L;
    }

    private static long randomDelayMs(long first, long second) {
        long low = Math.max(0L, Math.min(first, second));
        long high = Math.max(low, Math.max(first, second));
        if (high <= low) return low;
        // nextLong's bound is exclusive and would overflow at Long.MAX_VALUE.
        if (high == Long.MAX_VALUE) return high;
        return ThreadLocalRandom.current().nextLong(low, high + 1);
    }

    private static long deadline(long now, long delay) {
        long value = now + delay;
        return value < now ? Long.MAX_VALUE : value;
    }
}
