package dev.yanianz.bravenspawner.hook;

import dev.yanianz.bravenspawner.BravenSpawner;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.UUID;

/** Builds the island hook for the installed island plugin, keeping its classes out of the way when absent. */
public final class IslandHooks {

    static final String SUPERIOR = "SuperiorSkyblock2";

    private IslandHooks() {}

    /** Settings under {@code superiorskyblock} in config.yml. */
    public record Settings(boolean enabled, boolean islandPermissions, boolean shareSpawners, boolean multipliers,
                           boolean removeOnDisband, boolean autosellRequiresMembership, double maxMultiplier,
                           String usePrivilege, String placePrivilege, String breakPrivilege) {

        public static Settings load(ConfigurationSection root) {
            ConfigurationSection s = root == null ? null : root.getConfigurationSection("superiorskyblock");
            if (s == null) s = new YamlConfiguration();
            double max = s.getDouble("max-multiplier", 100.0);
            return new Settings(
                    s.getBoolean("enabled", true),
                    s.getBoolean("island-permissions", true),
                    s.getBoolean("members-share-spawners", true),
                    s.getBoolean("multipliers", true),
                    s.getBoolean("remove-spawners-on-disband", true),
                    s.getBoolean("autosell-requires-membership", true),
                    Double.isFinite(max) && max >= 1.0 ? max : 100.0,
                    privilege(s.getString("privileges.use"), "CHEST_ACCESS"),
                    privilege(s.getString("privileges.place"), "BUILD"),
                    privilege(s.getString("privileges.break"), "SPAWNER_BREAK"));
        }

        private static String privilege(String raw, String fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            return raw.trim().toUpperCase(Locale.ROOT);
        }

        /** Only names in our own namespace are ever registered with the island plugin. */
        static boolean custom(String privilege) {
            return privilege.startsWith("BRAVENSPAWNER_");
        }
    }

    /** onLoad: registers our custom privilege names before the island plugin loads its data. */
    public static void registerPrivileges(BravenSpawner plugin) {
        Plugin installed = plugin.getServer().getPluginManager().getPlugin(SUPERIOR);
        if (installed == null) return;
        Settings settings = Settings.load(plugin.getConfig());
        if (!settings.enabled()) return;
        try {
            SuperiorSkyblockHook.registerCustom(settings);
        } catch (RuntimeException | LinkageError error) {
            plugin.getLogger().warning("could not register SuperiorSkyblock2 privileges: " + error);
        }
    }

    /** onEnable: the hook for the installed island plugin, or {@link IslandHook#NONE}. */
    public static IslandHook create(BravenSpawner plugin) {
        Plugin installed = plugin.getServer().getPluginManager().getPlugin(SUPERIOR);
        if (installed == null) return IslandHook.NONE;
        Settings settings = Settings.load(plugin.getConfig());
        if (!settings.enabled()) {
            plugin.getLogger().info("SuperiorSkyblock2 found; integration disabled by superiorskyblock.enabled.");
            return IslandHook.NONE;
        }
        if (!installed.isEnabled()) {
            plugin.getLogger().warning("SuperiorSkyblock2 is installed but not enabled; spawner access on its"
                    + " worlds is denied until it loads.");
            return failClosed();
        }
        try {
            IslandHook hook = new SuperiorSkyblockHook(plugin, settings);
            plugin.getLogger().info("SuperiorSkyblock2 integration: island permissions="
                    + settings.islandPermissions() + ", shared spawners=" + settings.shareSpawners()
                    + ", multipliers=" + settings.multipliers() + ", disband cleanup=" + settings.removeOnDisband());
            return hook;
        } catch (RuntimeException | LinkageError error) {
            plugin.getLogger().warning("SuperiorSkyblock2 API unavailable (" + error + "); spawner access denied.");
            return failClosed();
        }
    }

    /** A broken island plugin must not silently become "no island here". */
    static IslandHook failClosed() {
        return new IslandHook() {
            @Override public String name() { return "superiorskyblock2 (unavailable)"; }
            @Override public Verdict check(Player player, Location location, Action action) { return Verdict.DENY; }
            @Override public boolean member(UUID player, Location location) { return false; }
            @Override public double spawnRateMultiplier(Location location) { return 1.0; }
            @Override public double dropMultiplier(Location location) { return 1.0; }
            @Override public boolean sharesSpawners() { return false; }
            @Override public boolean requiresMembershipForAutosell() { return true; }
        };
    }
}
