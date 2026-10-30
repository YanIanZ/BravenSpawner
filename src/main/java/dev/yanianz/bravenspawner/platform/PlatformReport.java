package dev.yanianz.bravenspawner.platform;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * One startup line that says what this server actually is.
 *
 * <p>Written after a report of "plugin is disabled" on a region-threaded fork:
 * without knowing the server brand, the API version it accepts and whether the
 * legacy bridge is involved, a boot failure is indistinguishable from a
 * configuration mistake. This prints those facts once, at INFO, before any
 * subsystem starts.
 */
public final class PlatformReport {

    private final JavaPlugin plugin;

    public PlatformReport(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void log() {
        List<String> facts = new ArrayList<>();

        facts.add("server=" + Bukkit.getName() + " " + Bukkit.getVersion());
        facts.add("minecraft=" + Bukkit.getMinecraftVersion());
        facts.add("java=" + System.getProperty("java.version"));
        facts.add("region-scheduler-api=" + regionThreaded());
        facts.add("region-threaded=" + (dev.yanianz.bravenspawner.util.Schedulers.isFolia() ? "yes" : "no"));
        facts.add("folia-api=" + present("io.papermc.paper.threadedregions.RegionizedServer"));
        facts.add("sourbyapi=" + pluginPresent("sourbyapi"));
        facts.add("sourbycraft=" + pluginPresent("SourbyCraft"));
        facts.add("aurora-bridge=" + pluginPresent("AuroraBridge"));

        plugin.getLogger().info("platform: " + String.join(" ", facts));
    }

    /**
     * A fork can keep Paper's class names while running region threads; probing
     * the scheduler entry point is cheaper and more honest than trusting the brand.
     */
    private String regionThreaded() {
        try {
            Bukkit.class.getMethod("getRegionScheduler");
            return "yes";
        } catch (NoSuchMethodException e) {
            return "no";
        }
    }

    private String present(String className) {
        try {
            Class.forName(className, false, plugin.getClass().getClassLoader());
            return "yes";
        } catch (ClassNotFoundException | LinkageError e) {
            return "no";
        }
    }

    private String pluginPresent(String name) {
        try {
            Plugin other = Bukkit.getPluginManager().getPlugin(name);
            return other == null ? "no" : other.getPluginMeta().getVersion();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
