package dev.yanianz.bravenspawner.boosters;

import dev.yanianz.bravenspawner.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Timed multipliers, per player and per spawner.
 *
 * <p>The multiplier is read at the moment of production and never cached into the
 * spawner, so an expiring booster takes effect immediately and a restart clears
 * everything predictably.
 */
public final class Boosters {

    private record Active(double multiplier, long expiresAt) {}

    private final Plugin plugin;
    private final Map<UUID, Active> byPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Active> bySpawner = new ConcurrentHashMap<>();

    public Boosters(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start(Settings settings) {
        if (!settings.enableBoosters) return;
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 200L, 200L);
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        byPlayer.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
        bySpawner.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
    }

    public void grantPlayer(UUID player, double multiplier, long seconds) {
        byPlayer.put(player, new Active(multiplier, System.currentTimeMillis() + seconds * 1000));
    }

    public void grantSpawner(UUID spawner, double multiplier, long seconds) {
        bySpawner.put(spawner, new Active(multiplier, System.currentTimeMillis() + seconds * 1000));
    }

    /** Highest applicable multiplier; 1.0 when nothing is active. */
    public double multiplier(UUID owner, UUID spawner) {
        long now = System.currentTimeMillis();
        double a = owner == null ? 1.0 : value(byPlayer.get(owner), now);
        double b = spawner == null ? 1.0 : value(bySpawner.get(spawner), now);
        return Math.max(a, b);
    }

    private double value(Active active, long now) {
        return active == null || active.expiresAt() <= now ? 1.0 : active.multiplier();
    }

    public long remainingSeconds(UUID player) {
        Active active = byPlayer.get(player);
        if (active == null) return 0;
        return Math.max(0, (active.expiresAt() - System.currentTimeMillis()) / 1000);
    }
}
