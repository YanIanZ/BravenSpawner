package dev.yanianz.bravenspawner.particles;

import dev.yanianz.bravenspawner.util.Schedulers;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Particles shown around placed spawners, configured under {@code particles} in
 * {@code config.yml}.
 *
 * <p>{@link Effect#SPAWNER} plays on every production cycle from the region thread
 * that runs the cycle. The one-shot bursts (stack, upgrade, booster) may be
 * triggered from any thread and are re-dispatched to the spawner's region, so
 * the world is never touched from a foreign thread on Folia.
 *
 * <p>{@link Effect#CORRUPTED} is the ambient effect of a corrupted spawner, played
 * every {@code particles.corrupted.interval-ticks} by
 * {@link dev.yanianz.bravenspawner.corruption.CorruptionEffects} on the spawner's
 * region; {@link Effect#CORRUPT} bursts once when a spawner becomes corrupted.
 */
public final class SpawnerParticles {

    /** Configurable effects; the key is the section name under {@code particles}. */
    public enum Effect {
        SPAWNER("spawner", new ParticleSpec.Defaults(true, "HAPPY_VILLAGER", 6, 0.35, 0.0, 16.0, "#7EE787", 1.0f)),
        STACK("stack", new ParticleSpec.Defaults(true, "TOTEM_OF_UNDYING", 20, 0.4, 0.15, 16.0, "#FFD93D", 1.0f)),
        UPGRADE("upgrade", new ParticleSpec.Defaults(true, "ENCHANT", 40, 0.5, 0.6, 16.0, "#C780FA", 1.0f)),
        BOOSTER("booster", new ParticleSpec.Defaults(true, "DUST", 24, 0.45, 0.0, 16.0, "#4D96FF", 1.2f)),
        CORRUPTED("corrupted", new ParticleSpec.Defaults(true, "DUST", 6, 0.45, 0.0, 24.0, "#8A2BE2", 1.3f)),
        CORRUPT("corrupt", new ParticleSpec.Defaults(true, "REVERSE_PORTAL", 80, 0.45, 0.08, 24.0, "#8A2BE2", 1.0f));

        final String key;
        final ParticleSpec.Defaults defaults;

        Effect(String key, ParticleSpec.Defaults defaults) {
            this.key = key;
            this.defaults = defaults;
        }
    }

    private final Plugin plugin;
    private final Set<String> warned = new HashSet<>();
    private volatile Map<Effect, ParticleSpec> specs = Map.of();
    private volatile int corruptedInterval = 10;
    private volatile int corruptedSpiralPoints = 8;

    public SpawnerParticles(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Re-reads {@code particles.*}; a malformed section is logged once per reload. */
    public void reload() {
        synchronized (this) { warned.clear(); }
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("particles");
        boolean master = root == null || root.getBoolean("enabled", true);
        Map<Effect, ParticleSpec> loaded = new EnumMap<>(Effect.class);
        if (master) {
            for (Effect effect : Effect.values()) {
                ConfigurationSection section = root == null ? null : root.getConfigurationSection(effect.key);
                ParticleSpec spec = ParticleSpec.read(section, "particles." + effect.key, effect.defaults, this::warnOnce);
                if (spec != null) loaded.put(effect, spec);
            }
        }
        specs = Map.copyOf(loaded);
        ConfigurationSection corrupted = root == null ? null : root.getConfigurationSection(Effect.CORRUPTED.key);
        corruptedInterval = corrupted == null ? 10 : clamp(corrupted.getInt("interval-ticks", 10), 5, 200);
        corruptedSpiralPoints = corrupted == null ? 8 : clamp(corrupted.getInt("spiral-points", 8), 0, 32);
    }

    /** Ticks between two ambient shows of a corrupted spawner. */
    public int corruptedInterval() { return corruptedInterval; }

    /** True when the ambient corrupted effect is configured and enabled. */
    public boolean corruptedEnabled() { return specs.containsKey(Effect.CORRUPTED); }

    /**
     * Ambient effect of a corrupted spawner: a cloud around the block plus two
     * strands spiralling up its sides, rotated by {@code frame}. The caller is on
     * the spawner's region thread.
     */
    public void corrupted(Location block, long frame) {
        ParticleSpec spec = specs.get(Effect.CORRUPTED);
        if (spec == null || block == null || block.getWorld() == null) return;
        try {
            Location centre = centre(block);
            spec.show(centre);
            int points = corruptedSpiralPoints;
            if (points == 0 || centre.getWorld().getNearbyPlayers(centre, spec.viewDistance()).isEmpty()) return;
            double turn = frame * 0.6;
            for (int i = 0; i < points; i++) {
                double progress = (double) i / points;
                double angle = turn + progress * Math.PI * 2.0;
                double y = centre.getY() - 0.5 + progress * 1.4;
                for (int strand = 0; strand < 2; strand++) {
                    double a = angle + strand * Math.PI;
                    spec.point(centre.getWorld(), centre.getX() + Math.cos(a) * 0.75, y, centre.getZ() + Math.sin(a) * 0.75);
                }
            }
        } catch (RuntimeException e) {
            warnOnce("particles.corrupted could not be shown: " + e.getMessage());
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Production cycle effect; the caller is already on the spawner's region thread. */
    public void cycle(Location block) {
        ParticleSpec spec = specs.get(Effect.SPAWNER);
        if (spec == null || block == null) return;
        try {
            spec.show(centre(block));
        } catch (RuntimeException e) {
            warnOnce("particles.spawner could not be shown: " + e.getMessage());
        }
    }

    /** One-shot burst; safe from any thread. */
    public void burst(Effect effect, Location block) {
        ParticleSpec spec = specs.get(effect);
        if (spec == null || block == null || block.getWorld() == null) return;
        Location centre = centre(block);
        try {
            Schedulers.at(plugin, centre, () -> {
                try {
                    spec.show(centre);
                } catch (RuntimeException e) {
                    warnOnce("particles." + effect.key + " could not be shown: " + e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            warnOnce("particles." + effect.key + " could not be scheduled: " + e.getMessage());
        }
    }

    private static Location centre(Location block) {
        return new Location(block.getWorld(), block.getBlockX() + 0.5, block.getBlockY() + 0.5, block.getBlockZ() + 0.5);
    }

    private synchronized void warnOnce(String message) {
        if (warned.add(message)) plugin.getLogger().warning(message);
    }
}
