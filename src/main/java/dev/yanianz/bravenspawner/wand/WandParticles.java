package dev.yanianz.bravenspawner.wand;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.particles.ParticleSpec;
import dev.yanianz.bravenspawner.util.Schedulers;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Set;

/**
 * The {@code particles} section of {@code sellwand.yml}: after a successful sale a
 * ring of particles expands around every sold spawner, and an optional trail
 * runs from the seller to each of them.
 *
 * <p>Each ring is drawn on the region that owns its spawner, one radius step per
 * {@code update-frequency} ticks when {@code gradual} is on. The trail is sent to
 * the seller only, from the seller's own thread, so no world state outside the
 * caller's region is touched.
 */
final class WandParticles {

    static final int MAX_SPAWNERS = 16;
    static final int MAX_STEPS = 40;
    static final int MAX_POINTS = 96;

    private static final ParticleSpec.Defaults DEFAULTS =
            new ParticleSpec.Defaults(true, "DUST", 1, 0.0, 0.0, 32.0, "RED", 1.0f);

    private final BravenSpawner plugin;
    private final Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile ParticleSpec spec;
    private volatile double[] radii = new double[0];
    private volatile double density = 2.0;
    private volatile long period = 1L;
    private volatile boolean trail = true;

    WandParticles(BravenSpawner plugin) {
        this.plugin = plugin;
    }

    void reload(YamlConfiguration yaml) {
        warned.clear();
        ConfigurationSection section = yaml == null ? null : yaml.getConfigurationSection("particles");
        ParticleSpec loaded = ParticleSpec.read(section, "sellwand.yml particles", DEFAULTS,
                message -> WandFiles.warnOnce(plugin, warned, message, message));
        boolean gradual = section == null || section.getBoolean("gradual.enabled", true);
        double start = section == null ? 0.1 : section.getDouble("gradual.start-radius", 0.1);
        double growth = section == null ? 0.1 : section.getDouble("gradual.growth-rate", 0.1);
        double max = section == null ? 1.0 : section.getDouble("max-radius", 1.0);
        this.radii = radii(start, growth, max, gradual);
        this.density = clamp(section == null ? 2.0 : section.getDouble("density", 2.0), 0.25, 16.0);
        this.period = (long) clamp(section == null ? 1 : section.getInt("update-frequency", 1), 1, 20);
        this.trail = section == null || section.getBoolean("trail", true);
        this.spec = loaded;
    }

    /** Plays the sale effect; called on the seller's thread. */
    void play(Player player, List<Location> sold) {
        ParticleSpec current = spec;
        if (current == null || player == null || sold.isEmpty()) return;
        double[] steps = radii;
        int shown = 0;
        for (Location block : sold) {
            if (shown++ >= MAX_SPAWNERS) break;
            if (block == null || block.getWorld() == null) continue;
            Location centre = new Location(block.getWorld(),
                    block.getBlockX() + 0.5, block.getBlockY() + 0.5, block.getBlockZ() + 0.5);
            try {
                if (trail) trail(current, player, centre);
                ring(current, centre, steps, 0);
            } catch (RuntimeException e) {
                WandFiles.warnOnce(plugin, warned, "play", "sell wand particles failed: " + e.getMessage());
            }
        }
    }

    private void ring(ParticleSpec current, Location centre, double[] steps, int index) {
        if (index >= steps.length) return;
        Runnable draw = () -> {
            World world = centre.getWorld();
            if (world == null || !world.isChunkLoaded(centre.getBlockX() >> 4, centre.getBlockZ() >> 4)) return;
            double radius = steps[index];
            int points = points(radius, density);
            for (int i = 0; i < points; i++) {
                double angle = 2 * Math.PI * i / points;
                current.point(world, centre.getX() + radius * Math.cos(angle), centre.getY(),
                        centre.getZ() + radius * Math.sin(angle));
            }
            ring(current, centre, steps, index + 1);
        };
        if (index == 0) Schedulers.at(plugin, centre, draw);
        else Schedulers.atLater(plugin, centre, period, draw);
    }

    private void trail(ParticleSpec current, Player player, Location target) {
        Location eye = player.getEyeLocation().subtract(0, 0.4, 0);
        if (eye.getWorld() == null || !eye.getWorld().equals(target.getWorld())) return;
        Vector delta = target.toVector().subtract(eye.toVector());
        double length = delta.length();
        if (length < 0.5) return;
        int points = (int) Math.min(MAX_POINTS, Math.ceil(length * density));
        for (int i = 0; i <= points; i++) {
            double t = (double) i / points;
            player.spawnParticle(current.particle(), eye.getX() + delta.getX() * t, eye.getY() + delta.getY() * t,
                    eye.getZ() + delta.getZ() * t, 1, 0.0, 0.0, 0.0, current.speed(), current.data());
        }
    }

    /** Ring radii: one when not gradual, otherwise start..max by growth, at most {@link #MAX_STEPS}. */
    static double[] radii(double start, double growth, double max, boolean gradual) {
        double end = clamp(max, 0.1, 8.0);
        if (!gradual) return new double[] {end};
        double first = clamp(start, 0.0, end);
        double step = clamp(growth, 0.01, end);
        double raw = Math.floor((end - first) / step + 1e-9) + 1;
        if (raw > MAX_STEPS) step = (end - first) / (MAX_STEPS - 1);
        int count = (int) Math.min(MAX_STEPS, raw);
        double[] out = new double[count];
        for (int i = 0; i < count; i++) out[i] = Math.min(end, first + step * i);
        return out;
    }

    /** Points on a ring of {@code radius}: {@code density} per block of circumference, 6..{@link #MAX_POINTS}. */
    static int points(double radius, double density) {
        double circumference = 2 * Math.PI * Math.max(0.0, radius);
        return (int) Math.max(6, Math.min(MAX_POINTS, Math.ceil(circumference * density)));
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}
