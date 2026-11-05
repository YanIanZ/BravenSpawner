package dev.yanianz.bravenspawner.papi;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.hook.SuperiorSkyblockIslandLookup;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.Function;

/**
 * Registers the {@code bravenspawner} PlaceholderAPI expansion when the plugin is
 * installed, and owns its 30 s snapshot task.
 *
 * <p>PlaceholderAPI is compileOnly and soft, so nothing here names its types in a
 * signature: the expansion is created inside a {@code try} that also catches
 * {@link LinkageError}, which is what a server without PlaceholderAPI raises when
 * {@link BravenSpawnerExpansion} is linked. Installing is therefore a no-op — not a
 * failure — on servers that do not have it.
 */
public final class PapiHook {

    private final SpawnerStatsCache stats;
    private final BravenSpawnerExpansion expansion;

    private PapiHook(SpawnerStatsCache stats, BravenSpawnerExpansion expansion) {
        this.stats = stats;
        this.expansion = expansion;
    }

    /**
     * Installs the expansion.
     *
     * @return the installed hook, or {@code null} when PlaceholderAPI is absent or
     *         the expansion could not be registered
     */
    public static PapiHook install(BravenSpawner plugin) {
        Plugin placeholderApi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (placeholderApi == null || !placeholderApi.isEnabled()) {
            plugin.debug("PlaceholderAPI not installed; bravenspawner expansion not registered");
            return null;
        }
        try {
            SpawnerStatsCache stats = new SpawnerStatsCache(plugin, islandLookup());
            BravenSpawnerExpansion expansion =
                    new BravenSpawnerExpansion(stats, plugin.getPluginMeta().getVersion());
            if (!expansion.register()) {
                plugin.getLogger().warning("PlaceholderAPI refused the bravenspawner expansion;"
                        + " placeholders will not resolve until the next start.");
                return null;
            }
            stats.start();
            plugin.getLogger().info("PlaceholderAPI expansion 'bravenspawner' registered: "
                    + "%bravenspawner_spawners_total% %bravenspawner_spawners_active%"
                    + " %bravenspawner_spawners_broken% %bravenspawner_spawners_status%");
            return new PapiHook(stats, expansion);
        } catch (RuntimeException | LinkageError unavailable) {
            plugin.getLogger().warning("PlaceholderAPI expansion not registered: " + unavailable);
            return null;
        }
    }

    /** SSB2 island lookup, or {@code null} when SSB2 is not installed. */
    private static Function<UUID, String> islandLookup() {
        Plugin superior = Bukkit.getPluginManager().getPlugin("SuperiorSkyblock2");
        if (superior == null || !superior.isEnabled()) return null;
        return SuperiorSkyblockIslandLookup::islandOf;
    }

    /** Counts this hook serves; exposed for diagnostics. */
    public SpawnerStatsCache stats() { return stats; }

    /** Unregisters the expansion and stops the refresh task; idempotent. */
    public void close() {
        stats.stop();
        try {
            expansion.unregister();
        } catch (RuntimeException | LinkageError ignored) {
            // PlaceholderAPI already gone (shutdown order): nothing left to unregister.
        }
    }
}
