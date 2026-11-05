package dev.yanianz.bravenspawner.papi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Locale;

/**
 * The {@code bravenspawner} PlaceholderAPI expansion.
 *
 * <p>The only class in the plugin (besides {@code platform.PlaceholderBridge}) that
 * names a PlaceholderAPI type; {@link PapiHook} links it only when the plugin is
 * installed, so a server without PlaceholderAPI never resolves it.
 *
 * <p>{@link #onRequest} can be called from any thread. It performs no world or
 * block access and no blocking call: it asks {@link SpawnerStatsCache} for the
 * requester's island id and then reads the last immutable snapshot (an SSB2
 * in-memory player lookup plus one map read).
 */
final class BravenSpawnerExpansion extends PlaceholderExpansion {

    static final String IDENTIFIER = "bravenspawner";

    private static final String GREEN = "\u00a7a";
    private static final String RED = "\u00a7c";
    static final String BROKEN_MARK = " \u26a0 corrupt";

    private final SpawnerStatsCache stats;
    private final String version;

    BravenSpawnerExpansion(SpawnerStatsCache stats, String version) {
        this.stats = stats;
        this.version = version == null ? "unknown" : version;
    }

    @Override public String getIdentifier() { return IDENTIFIER; }

    @Override public String getAuthor() { return "yanianz"; }

    @Override public String getVersion() { return version; }

    @Override
    public List<String> getPlaceholders() {
        return List.of("spawners_total", "spawners_active", "spawners_broken", "spawners_status");
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) return null;
        String key = params.toLowerCase(Locale.ROOT);
        boolean known = switch (key) {
            case "spawners_total", "spawners_active", "spawners_broken", "spawners_status" -> true;
            default -> false;
        };
        if (!known) return null;
        String island = stats.islandOf(player == null ? null : player.getUniqueId());
        SpawnerStatsCache.Stats value = stats.statsFor(island);
        return switch (key) {
            // No island: counts read 0 and the status stays empty, so a scoreboard
            // line renders blank instead of claiming a broken spawner.
            case "spawners_total" -> Integer.toString(value.total());
            case "spawners_active" -> Integer.toString(value.active());
            case "spawners_broken" -> Integer.toString(value.broken());
            default -> island == null ? "" : status(value);
        };
    }

    /** {@code 8/8} in green, or {@code 7/8 ⚠ corrupt} in red when a record is not active. */
    static String status(SpawnerStatsCache.Stats value) {
        String counts = value.active() + "/" + value.total();
        return value.broken() > 0 ? RED + counts + BROKEN_MARK : GREEN + counts;
    }
}
