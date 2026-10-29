package dev.yanianz.bravenspawner.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Folia-first scheduling with no reflection.
 *
 * <p>Paper 1.20+ ships the region scheduler API in paper-api, so the same calls
 * compile and run on both platforms: on Paper they execute on the main thread, on
 * Folia they land on the region that owns the target. Nothing here needs a
 * runtime lookup.
 */
public final class Schedulers {

    private static final boolean FOLIA = detectFolia();

    private Schedulers() {}

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** True when the server runs Folia; used only for the startup log line. */
    public static boolean isFolia() {
        return FOLIA;
    }

    public static void at(Plugin plugin, Location location, Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, location, task);
    }

    public static void onEntity(Plugin plugin, Entity entity, Runnable task, Runnable retired) {
        entity.getScheduler().execute(plugin, task, retired, 1L);
    }

    public static Cancelable repeating(Plugin plugin, Location location, long periodTicks, Runnable task) {
        ScheduledTask handle = Bukkit.getRegionScheduler()
                .runAtFixedRate(plugin, location, t -> task.run(), periodTicks, periodTicks);
        return () -> handle.cancel();
    }

    public static void later(Plugin plugin, long delayTicks, Runnable task) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delayTicks);
    }

    public static void async(Plugin plugin, Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
    }

    public static void asyncLater(Plugin plugin, long delayMillis, Runnable task) {
        Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), delayMillis, TimeUnit.MILLISECONDS);
    }

    public interface Cancelable {
        void cancel();
    }
}
