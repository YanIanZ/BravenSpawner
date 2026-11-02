package dev.yanianz.bravenspawner.corruption;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.holo.Holograms;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.particles.SpawnerParticles;
import dev.yanianz.bravenspawner.util.Schedulers;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plays the ambient particles of corrupted spawners and animates their glitching
 * holograms.
 *
 * <p>A global task wakes every {@value #STEP_TICKS} ticks and only reads the
 * volatile {@code corrupted} flag. Every corrupted spawner whose effect is due gets
 * one task on the region that owns it, which checks the chunk and nearby players
 * before touching anything, so an unwatched corrupted spawner costs nothing. A
 * spawner whose previous task has not run yet is skipped instead of queued twice.
 */
public final class CorruptionEffects {

    static final long STEP_TICKS = 5L;

    private final BravenSpawner plugin;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private volatile ScheduledTask task;
    private long ticks;

    public CorruptionEffects(BravenSpawner plugin) {
        this.plugin = plugin;
    }

    /** Starts the ticker; repeated calls are ignored while it is live. */
    public void start() {
        if (!plugin.isEnabled()) return;
        ScheduledTask current = task;
        if (current != null && !current.isCancelled()) return;
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> step(), STEP_TICKS, STEP_TICKS);
    }

    public void stop() {
        ScheduledTask current = task;
        task = null;
        if (current != null) {
            try {
                current.cancel();
            } catch (RuntimeException ignored) {
                // already retired during shutdown
            }
        }
        inFlight.clear();
    }

    private void step() {
        SpawnerManager manager = plugin.manager();
        if (!plugin.isEnabled() || !plugin.ready() || manager == null) return;
        ticks += STEP_TICKS;
        SpawnerParticles particles = plugin.particles();
        Holograms holograms = plugin.holograms();
        boolean showParticles = particles != null && particles.corruptedEnabled()
                && due(ticks, particles.corruptedInterval());
        boolean animate = holograms != null && holograms.config().enabled()
                && holograms.config().corruptedStyle().animated()
                && due(ticks, holograms.config().corruptedStyle().animationTicks());
        if (!showParticles && !animate) return;
        long frame = ticks / STEP_TICKS;
        for (Spawner spawner : manager.all()) {
            if (spawner == null || !spawner.corrupted()) continue;
            boolean hologram = animate && spawner.hologram();
            if (!showParticles && !hologram) continue;
            Location location = spawner.location();
            if (location == null || !inFlight.add(spawner.id())) continue;
            try {
                Schedulers.at(plugin, location, () -> {
                    try {
                        play(spawner, location, showParticles, hologram, frame);
                    } finally {
                        inFlight.remove(spawner.id());
                    }
                });
            } catch (RuntimeException e) {
                inFlight.remove(spawner.id());
            }
        }
    }

    /** Region thread: skips unloaded chunks and spawners nobody is near. */
    private void play(Spawner spawner, Location location, boolean showParticles, boolean hologram, long frame) {
        if (!plugin.isEnabled() || plugin.manager() == null || plugin.manager().byId(spawner.id()) != spawner
                || !spawner.corrupted()) return;
        World world = location.getWorld();
        if (world == null || !world.isChunkLoaded(spawner.x() >> 4, spawner.z() >> 4)) return;
        double range = Math.max(24.0, plugin.holograms() == null ? 0 : plugin.holograms().config().activationRadius());
        if (world.getNearbyPlayers(location, range).isEmpty()) return;
        if (showParticles) plugin.particles().corrupted(location, frame);
        if (hologram) plugin.holograms().update(spawner);
    }

    /** True on the step that completes another {@code interval} ticks. */
    static boolean due(long ticks, int interval) {
        long step = Math.max(STEP_TICKS, (interval + STEP_TICKS - 1) / STEP_TICKS * STEP_TICKS);
        return ticks % step == 0;
    }
}
