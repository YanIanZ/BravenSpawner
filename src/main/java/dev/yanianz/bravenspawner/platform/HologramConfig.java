package dev.yanianz.bravenspawner.platform;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parsed holograms.yml: the text each spawner's hologram shows and how often it is
 * refreshed.
 *
 * <p>Parsed once at enable into immutable lists. A hologram refresh touches every
 * visible spawner on a timer, so the render path must not read a live
 * {@link ConfigurationSection} — a reload swaps this whole object instead, which
 * means a refresh already in flight keeps rendering a consistent template.
 *
 * <p>Values are clamped rather than rejected. A hand-edited interval of 0 would
 * rewrite a display every tick and a view distance of 10 000 would track the whole
 * map; both are user error, not corruption, so they are folded into the nearest
 * sane value instead of disabling holograms.
 */
public final class HologramConfig {

    /** Product, stored count and stack size — the three numbers a player wants at a glance. */
    public static final List<String> DEFAULT_LINES = List.of(
            "&e%product%",
            "&7Stored: &f%stored%",
            "&7Stack: &f%stack%");

    public static final long DEFAULT_UPDATE_INTERVAL_TICKS = 40L;
    public static final int DEFAULT_VIEW_DISTANCE = 32;

    /** One hour. Past this a refresh is indistinguishable from "never". */
    private static final long MAX_UPDATE_INTERVAL_TICKS = 72_000L;

    /** Eight chunks, the practical edge of a display's usefulness. */
    private static final int MAX_VIEW_DISTANCE = 128;

    private final boolean enabled;
    private final long updateIntervalTicks;
    private final int viewDistance;
    private final List<String> lines;
    private final Map<String, TierOverride> tiers;

    private HologramConfig(boolean enabled, long updateIntervalTicks, int viewDistance,
                           List<String> lines, Map<String, TierOverride> tiers) {
        this.enabled = enabled;
        this.updateIntervalTicks = updateIntervalTicks;
        this.viewDistance = viewDistance;
        this.lines = List.copyOf(lines);
        this.tiers = Map.copyOf(tiers);
    }

    /**
     * Loads holograms.yml, writing the packaged default when the file is missing.
     *
     * <p>An unreadable file is logged and replaced with the built-in defaults
     * rather than rethrown: holograms are decoration, and a typo in one line of
     * YAML must not stop spawners from ticking.
     */
    public static HologramConfig load(BravenSpawner plugin) {
        File file = new File(plugin.getDataFolder(), "holograms.yml");
        if (!file.isFile()) {
            try {
                plugin.saveResource("holograms.yml", false);
            } catch (IllegalArgumentException missingFromJar) {
                plugin.getLogger().warning("holograms.yml is not packaged; holograms use built-in defaults");
            }
        }

        YamlConfiguration yaml = new YamlConfiguration();
        if (file.isFile()) {
            try {
                yaml.load(file);
            } catch (IOException | InvalidConfigurationException unreadable) {
                plugin.getLogger().warning("holograms.yml could not be read ("
                        + unreadable.getMessage() + "); using built-in defaults");
                return defaults();
            }
        }
        return from(yaml);
    }

    /** Parses an already-loaded section; separated so a reload can hand in any source. */
    public static HologramConfig from(ConfigurationSection root) {
        if (root == null) return defaults();

        long interval = clampInterval(longValue(root, "update-interval-ticks", DEFAULT_UPDATE_INTERVAL_TICKS));
        int distance = clampDistance(intValue(root, "view-distance", DEFAULT_VIEW_DISTANCE));

        List<String> lines = root.getStringList("lines");
        if (lines.isEmpty()) lines = DEFAULT_LINES;

        Map<String, TierOverride> overrides = new LinkedHashMap<>();
        ConfigurationSection tierSection = root.getConfigurationSection("tiers");
        if (tierSection != null) {
            for (String key : tierSection.getKeys(false)) {
                ConfigurationSection tier = tierSection.getConfigurationSection(key);
                if (tier == null || key == null || key.isBlank()) continue;

                List<String> tierLines = tier.getStringList("lines");
                Long tierInterval = tier.isSet("update-interval-ticks")
                        ? clampInterval(longValue(tier, "update-interval-ticks", interval))
                        : null;
                Integer tierDistance = tier.isSet("view-distance")
                        ? clampDistance(intValue(tier, "view-distance", distance))
                        : null;

                // An override whose lines list was emptied falls back to the default
                // template: silently blanking a hologram is never what an operator
                // meant by "I'll fix this tier later".
                overrides.put(key.toLowerCase(Locale.ROOT), new TierOverride(
                        tierLines.isEmpty() ? null : List.copyOf(tierLines), tierInterval, tierDistance));
            }
        }

        return new HologramConfig(
                root.getBoolean("enabled", true), interval, distance, lines, overrides);
    }

    /** The packaged defaults, for a missing or unreadable file. */
    public static HologramConfig defaults() {
        return new HologramConfig(true, DEFAULT_UPDATE_INTERVAL_TICKS, DEFAULT_VIEW_DISTANCE,
                DEFAULT_LINES, Map.of());
    }

    public boolean enabled() { return enabled; }

    public long updateIntervalTicks() { return updateIntervalTicks; }

    public int viewDistance() { return viewDistance; }

    public List<String> defaultLines() { return lines; }

    /**
     * The finished lines for one spawner, placeholders already substituted.
     *
     * <p>The caller's map wins over the values read from the spawner, so a GUI or a
     * scoreboard can override one number without rebuilding the whole map. An empty
     * map is valid and renders product/stored/stack from the model alone.
     *
     * <p>Returns an empty list when holograms are disabled or the spawner is null,
     * so callers can pass the result straight to a display without a null check.
     */
    public List<String> lines(Spawner spawner, Map<String, String> placeholders) {
        if (!enabled || spawner == null) return List.of();
        Map<String, String> tokens = tokens(spawner, placeholders);
        List<String> template = templateFor(tokens.get("tier"));
        List<String> out = new ArrayList<>(template.size());
        for (String line : template) out.add(substitute(line, tokens));
        return out;
    }

    /** Refresh period for one spawner, honouring a per-tier override. */
    public long updateIntervalTicks(Spawner spawner, Map<String, String> placeholders) {
        TierOverride override = overrideFor(tokens(spawner, placeholders).get("tier"));
        return override != null && override.updateIntervalTicks != null
                ? override.updateIntervalTicks : updateIntervalTicks;
    }

    /** View distance for one spawner, honouring a per-tier override. */
    public int viewDistance(Spawner spawner, Map<String, String> placeholders) {
        TierOverride override = overrideFor(tokens(spawner, placeholders).get("tier"));
        return override != null && override.viewDistance != null ? override.viewDistance : viewDistance;
    }

    // ------------------------------------------------------------- internals

    /**
     * Builds the substitution table: model values first, caller values on top.
     *
     * <p>Keys are normalised to lower case without the surrounding '%', which is
     * what lets {@code %Product%} in a hand-written template resolve without the
     * operator having to remember the case.
     */
    private static Map<String, String> tokens(Spawner spawner, Map<String, String> placeholders) {
        Map<String, String> tokens = new HashMap<>();
        if (spawner != null) {
            tokens.put("product", spawner.product() == null ? "" : spawner.product());
            tokens.put("kind", spawner.kind() == null ? "" : spawner.kind().name().toLowerCase(Locale.ROOT));
            tokens.put("stack", String.valueOf(spawner.stack()));
            tokens.put("stored", String.valueOf(spawner.storedCount()));
            tokens.put("slots", String.valueOf(spawner.distinctSlots()));
            tokens.put("owner", spawner.owner() == null ? "" : spawner.owner().toString().substring(0, 8));
            tokens.put("world", spawner.world() == null ? "" : spawner.world());
            tokens.put("x", String.valueOf(spawner.x()));
            tokens.put("y", String.valueOf(spawner.y()));
            tokens.put("z", String.valueOf(spawner.z()));
            tokens.put("tier", String.valueOf(spawner.tier()));
        }
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                if (entry.getKey() == null) continue;
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (key.startsWith("%") && key.endsWith("%") && key.length() > 2) {
                    key = key.substring(1, key.length() - 1);
                }
                tokens.put(key, entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return tokens;
    }

    private List<String> templateFor(String tierKey) {
        TierOverride override = overrideFor(tierKey);
        return override != null && override.lines != null ? override.lines : lines;
    }

    private TierOverride overrideFor(String tierKey) {
        if (tierKey == null || tiers.isEmpty()) return null;
        return tiers.get(tierKey.toLowerCase(Locale.ROOT));
    }

    /**
     * Replaces {@code %name%} tokens in one pass.
     *
     * <p>A scanner rather than a chain of {@code String.replace} calls: replacements
     * are looked up case-insensitively and an unknown token is left exactly as
     * written, so a typo in the YAML shows up in-game instead of disappearing.
     * Nothing in a substituted value is re-scanned, so a product name containing a
     * '%' cannot inject a second token.
     */
    private static String substitute(String line, Map<String, String> tokens) {
        if (line == null) return "";
        int open = line.indexOf('%');
        if (open < 0) return line;

        StringBuilder out = new StringBuilder(line.length() + 16);
        int cursor = 0;
        while (open >= 0) {
            int close = line.indexOf('%', open + 1);
            if (close < 0) break;
            out.append(line, cursor, open);
            String key = line.substring(open + 1, close).toLowerCase(Locale.ROOT);
            String value = tokens.get(key);
            if (value == null) out.append(line, open, close + 1);
            else out.append(value);
            cursor = close + 1;
            open = line.indexOf('%', cursor);
        }
        out.append(line, cursor, line.length());
        return out.toString();
    }

    private static long clampInterval(long raw) {
        return Math.max(1L, Math.min(MAX_UPDATE_INTERVAL_TICKS, raw));
    }

    private static int clampDistance(int raw) {
        return Math.max(1, Math.min(MAX_VIEW_DISTANCE, raw));
    }

    /**
     * Reads a long without trusting the file.
     *
     * <p>Bukkit's typed getters throw on a value of the wrong shape (a quoted "40"
     * reads as a String, not a Number). A single bad key should cost that key, not
     * the whole hologram system.
     */
    private static long longValue(ConfigurationSection section, String path, long fallback) {
        try {
            return section.getLong(path, fallback);
        } catch (RuntimeException wrongShape) {
            return fallback;
        }
    }

    private static int intValue(ConfigurationSection section, String path, int fallback) {
        try {
            return section.getInt(path, fallback);
        } catch (RuntimeException wrongShape) {
            return fallback;
        }
    }

    /** Per-tier template and rate; a null field means "inherit the default". */
    private static final class TierOverride {
        final List<String> lines;
        final Long updateIntervalTicks;
        final Integer viewDistance;

        TierOverride(List<String> lines, Long updateIntervalTicks, Integer viewDistance) {
            this.lines = lines;
            this.updateIntervalTicks = updateIntervalTicks;
            this.viewDistance = viewDistance;
        }
    }
}
