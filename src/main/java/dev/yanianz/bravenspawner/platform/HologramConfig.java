package dev.yanianz.bravenspawner.platform;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hologram settings, read from the original plugin's {@code holograms.yml}.
 *
 * <p>The file is shipped verbatim from VirtualSpawner, so the keys here are that
 * plugin's keys — {@code hologram.content}, {@code bar-length}, {@code bar-char},
 * the corrupted palette. Parsing our own schema instead would have produced a
 * hologram that looks almost right, which is worse than not supporting it at all.
 *
 * <p>Placeholders resolved per line: {@code %stack%}, {@code %spawner_type_cap%},
 * {@code %storage_bar%}, {@code %xp_bar%}, {@code %color%},
 * {@code %corruption_status%}.
 *
 * <p>The reference format writes hex colours bare ({@code #c2c2c2}); hologram
 * text is rewritten to {@code &#c2c2c2} before rendering so the shipped content
 * shows colours instead of literal hex codes.
 */
public final class HologramConfig {

    private static final List<String> FALLBACK_CONTENT = List.of(
            "#c2c2c2(#fff9c2%stack%#969696x#c2c2c2) %color%&l%spawner_type_cap% SPAWNER %corruption_status%",
            "#34eb98☁ ѕᴛoʀᴀɢᴇ #6e6d6d➤ %storage_bar%",
            "#fab170❆ xᴘ #6e6d6d➤ %xp_bar%");

    private final boolean enabled;
    private final String type;
    private final boolean toggleCommandEnabled;
    private final List<String> content;
    private final int barLength;
    private final String barChar;
    private final String barCompletedColor;
    private final String barEmptyColor;
    private final int activationRadius;
    private final String corruptedColor;
    private final String corruptedChars;
    private final String corruptedText;

    private HologramConfig(boolean enabled, String type, boolean toggleCommandEnabled, List<String> content,
                           int barLength, String barChar, String barCompletedColor, String barEmptyColor,
                           int activationRadius, String corruptedColor, String corruptedChars, String corruptedText) {
        this.enabled = enabled;
        this.type = type;
        this.toggleCommandEnabled = toggleCommandEnabled;
        this.content = List.copyOf(content);
        this.barLength = barLength;
        this.barChar = barChar;
        this.barCompletedColor = barCompletedColor;
        this.barEmptyColor = barEmptyColor;
        this.activationRadius = activationRadius;
        this.corruptedColor = corruptedColor;
        this.corruptedChars = corruptedChars;
        this.corruptedText = corruptedText;
    }

    public static HologramConfig load(BravenSpawner plugin) {
        File file = new File(plugin.getDataFolder(), "holograms.yml");
        if (!file.exists()) plugin.saveResource("holograms.yml", false);
        if (!file.exists()) return defaults();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return from(yaml);
    }

    public static HologramConfig from(ConfigurationSection root) {
        if (root == null) return defaults();
        ConfigurationSection holo = root.getConfigurationSection("hologram");
        List<String> content = holo == null ? FALLBACK_CONTENT : holo.getStringList("content");
        if (content.isEmpty()) content = FALLBACK_CONTENT;

        ConfigurationSection toggle = root.getConfigurationSection("commands.shtoggle");

        return new HologramConfig(
                root.getBoolean("enabled", false),
                root.getString("type", "decentholograms"),
                toggle != null && toggle.getBoolean("enabled", false),
                content,
                clamp(holo == null ? 12 : holo.getInt("bar-length", 12), 1, 64),
                holo == null ? "☰" : holo.getString("bar-char", "☰"),
                holo == null ? "#00ff00" : holo.getString("bar-completed-color", "#00ff00"),
                holo == null ? "#dedede" : holo.getString("bar-not-completed-color", "#dedede"),
                clamp(holo == null ? 10 : holo.getInt("activation-radius", 10), 1, 128),
                holo == null ? "#8A2BE2" : holo.getString("corrupted-color", "#8A2BE2"),
                holo == null ? "" : holo.getString("corrupted-chars", ""),
                holo == null ? "&5&l[CORRUPTED]" : holo.getString("corrupted-text", "&5&l[CORRUPTED]"));
    }

    public static HologramConfig defaults() {
        return new HologramConfig(false, "decentholograms", false, FALLBACK_CONTENT,
                12, "☰", "#00ff00", "#dedede", 10, "#8A2BE2", "", "&5&l[CORRUPTED]");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public boolean enabled() { return enabled; }
    public String type() { return type; }
    public boolean toggleCommandEnabled() { return toggleCommandEnabled; }
    public int activationRadius() { return activationRadius; }
    public List<String> content() { return content; }

    /** Kept for callers written against the earlier API. */
    public long updateIntervalTicks() { return 40L; }
    public int viewDistance() { return activationRadius; }
    public List<String> defaultLines() { return content; }

    /** Rendering entry point without capacity information; bars render empty. */
    public List<String> lines(Spawner spawner, Map<String, String> extra) {
        return lines(spawner, extra, 0, 0);
    }

    /**
     * Every line of the configured content, substituted.
     *
     * @param maximumItems the spawner's item capacity; 0 or less means unlimited
     * @param maximumXp    the spawner's XP capacity; 0 or less means unlimited
     */
    public List<String> lines(Spawner spawner, Map<String, String> extra, int maximumItems, int maximumXp) {
        List<String> out = new ArrayList<>(content.size());
        for (String line : content) out.add(bareHex(resolve(line, spawner, extra, maximumItems, maximumXp)));
        return out;
    }

    private static final java.util.regex.Pattern BARE_HEX =
            java.util.regex.Pattern.compile("(?<![&{<:#\\\\])#([0-9a-fA-F]{6})");

    /** Rewrites reference-style {@code #RRGGBB} into {@code &#RRGGBB}; other forms are untouched. */
    public static String bareHex(String line) {
        if (line == null || line.indexOf('#') < 0) return line;
        return BARE_HEX.matcher(line).replaceAll("&#$1");
    }

    /** Ratio for a progress bar; an unlimited (non-positive) capacity reads as empty. */
    static double ratio(long value, long capacity) {
        if (capacity <= 0 || value <= 0) return 0.0;
        return Math.min(1.0, (double) value / capacity);
    }

    private String resolve(String line, Spawner spawner, Map<String, String> extra, int maximumItems, int maximumXp) {
        String result = line;

        int stack = spawner == null ? 1 : spawner.stack();
        String product = spawner == null ? "SPAWNER" : spawner.product();

        result = result.replace("%stack%", String.valueOf(stack));
        result = result.replace("%spawner_type_cap%", product.toUpperCase(Locale.ROOT));
        result = result.replace("%spawner_type%", product);
        result = result.replace("%color%", colorFor(product));
        result = result.replace("%storage_bar%", bar(spawner == null ? 0 : ratio(spawner.storedCount(), maximumItems)));
        result = result.replace("%xp_bar%", bar(spawner == null ? 0 : ratio(spawner.storedXp(), maximumXp)));

        boolean corrupted = spawner != null && spawner.corrupted();
        result = result.replace("%corruption_status%",
                corrupted ? corruptedColor + corruptedText + (corruptedChars.isEmpty() ? "" : corruptedChars) : "");

        if (extra != null) {
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                result = result.replace("%" + entry.getKey() + "%", entry.getValue());
            }
        }
        return result;
    }

    private static final String[] PALETTE = {"#ff6b6b", "#6bcb77", "#4d96ff", "#ffd93d", "#c780fa", "#ff9f45"};

    /** Deterministic per-product colour so the same mob always looks the same. */
    private static String colorFor(String product) {
        return PALETTE[Math.floorMod(product == null ? 0 : product.hashCode(), PALETTE.length)];
    }

    /** The per-product accent as a colour code ({@code &#RRGGBB}), shared with the menus' {@code %color%}. */
    public static String productColor(String product) {
        return "&" + colorFor(product);
    }

    private String bar(double ratio) {
        int filled = (int) Math.round(ratio * barLength);
        StringBuilder sb = new StringBuilder(barLength * 6);
        sb.append(barCompletedColor);
        for (int i = 0; i < filled; i++) sb.append(barChar);
        sb.append(barEmptyColor);
        for (int i = filled; i < barLength; i++) sb.append(barChar);
        return sb.toString();
    }
}
