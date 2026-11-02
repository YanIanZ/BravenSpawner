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
import java.util.SplittableRandom;

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
 * {@code %corruption_status%}, {@code %glitch_type%}, {@code %corruption_pulse%},
 * {@code %spawner_type_small%} (the type in Unicode small capitals).
 *
 * <p>A corrupted spawner shows {@code hologram.corrupted.content} instead of
 * {@code content} (unless {@code hologram.corrupted.enabled} is false). Its glitch
 * text changes every {@code animation-ticks}: each frame is derived from the
 * spawner id and the frame number, so a frame renders the same way every time.
 *
 * <p>The reference format writes hex colours bare ({@code #c2c2c2}); hologram
 * text is rewritten to {@code &#c2c2c2} before rendering so the shipped content
 * shows colours instead of literal hex codes.
 */
public final class HologramConfig {

    private static final List<String> FALLBACK_CONTENT = List.of(
            "#c2c2c2(#fff9c2%stack%#969696×#c2c2c2) %color%&l%spawner_type_small% ꜱᴘᴀᴡɴᴇʀ %corruption_status%",
            "#34eb98☁ ꜱᴛᴏʀᴀɢᴇ #6e6d6d➤ %storage_bar%",
            "#fab170❆ xᴘ #6e6d6d➤ %xp_bar%");

    private static final List<String> FALLBACK_CORRUPTED_CONTENT = List.of(
            "#c2c2c2(#fff9c2%stack%#969696×#c2c2c2) %glitch_type% #6e6d6d&lꜱᴘᴀᴡɴᴇʀ",
            "%corruption_pulse%&l☠ ᴄᴏʀʀᴜᴘᴛᴇᴅ ☠",
            "#8a7f99✖ ᴘʀᴏᴅᴜᴄᴛɪᴏɴ ʜᴀʟᴛᴇᴅ",
            "#b48cff➤ #d9c7ffʀɪɢʜᴛ-ᴄʟɪᴄᴋ ᴛᴏ ʀᴇᴘᴀɪʀ");
    private static final List<String> FALLBACK_PULSE = List.of("#8A2BE2", "#B455FF", "#FF3CAC", "#B455FF");
    /** Vanilla's text-display background (25% black); restored when a spawner is repaired. */
    public static final int DEFAULT_BACKGROUND = 0x40000000;
    private static final int FALLBACK_CORRUPTED_BACKGROUND = 0x662A0845;
    /** Characters swapped into a glitched name. */
    private static final String GLITCH_GLYPHS = "▓▒░█▚▞▙▟◢◣◤◥⌁⌇⍉⍜⎔⏃⏚☍※";
    // Symbols only: #, & or % would merge with the colour codes and placeholders around them.

    /**
     * How a corrupted spawner's hologram looks.
     *
     * @param enabled        false keeps the normal content for corrupted spawners
     * @param background     text background as ARGB
     * @param animationTicks ticks per glitch frame; 0 renders one static frame
     */
    public record CorruptedStyle(boolean enabled, List<String> content, int background, int animationTicks,
                                 List<String> pulseColors) {
        public CorruptedStyle {
            content = List.copyOf(content);
            pulseColors = List.copyOf(pulseColors);
        }

        public boolean animated() { return enabled && animationTicks > 0; }
    }

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
    private final int[] corruptedMarks;
    private final CorruptedStyle corruptedStyle;

    private HologramConfig(boolean enabled, String type, boolean toggleCommandEnabled, List<String> content,
                           int barLength, String barChar, String barCompletedColor, String barEmptyColor,
                           int activationRadius, String corruptedColor, String corruptedChars, String corruptedText,
                           CorruptedStyle corruptedStyle) {
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
        this.corruptedMarks = corruptedChars == null ? new int[0] : corruptedChars.codePoints().toArray();
        this.corruptedStyle = corruptedStyle;
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
                holo == null ? "&5&l[ᴄᴏʀʀᴜᴘᴛᴇᴅ]" : holo.getString("corrupted-text", "&5&l[ᴄᴏʀʀᴜᴘᴛᴇᴅ]"),
                corruptedStyle(holo == null ? null : holo.getConfigurationSection("corrupted")));
    }

    /** Reads {@code hologram.corrupted}; a missing section means the shipped style. */
    static CorruptedStyle corruptedStyle(ConfigurationSection section) {
        if (section == null) return defaultCorruptedStyle();
        List<String> content = section.getStringList("content");
        List<String> pulse = section.getStringList("pulse-colors");
        return new CorruptedStyle(
                section.getBoolean("enabled", true),
                content.isEmpty() ? FALLBACK_CORRUPTED_CONTENT : content,
                argb(section.getString("background"), FALLBACK_CORRUPTED_BACKGROUND),
                clamp(section.getInt("animation-ticks", 10), 0, 200),
                pulse.isEmpty() ? FALLBACK_PULSE : pulse);
    }

    private static CorruptedStyle defaultCorruptedStyle() {
        return new CorruptedStyle(true, FALLBACK_CORRUPTED_CONTENT, FALLBACK_CORRUPTED_BACKGROUND, 10, FALLBACK_PULSE);
    }

    /** {@code #AARRGGBB}, or {@code #RRGGBB} at 40% opacity; {@code fallback} when malformed. */
    static int argb(String raw, int fallback) {
        if (raw == null) return fallback;
        String value = raw.trim();
        if (value.startsWith("&#")) value = value.substring(2);
        else if (value.startsWith("#")) value = value.substring(1);
        if (value.matches("[0-9a-fA-F]{6}")) return 0x66000000 | Integer.parseInt(value, 16);
        if (value.matches("[0-9a-fA-F]{8}")) return (int) Long.parseLong(value, 16);
        return fallback;
    }

    public static HologramConfig defaults() {
        return new HologramConfig(false, "decentholograms", false, FALLBACK_CONTENT,
                12, "☰", "#00ff00", "#dedede", 10, "#8A2BE2", "", "&5&l[ᴄᴏʀʀᴜᴘᴛᴇᴅ]", defaultCorruptedStyle());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public boolean enabled() { return enabled; }
    public String type() { return type; }
    public boolean toggleCommandEnabled() { return toggleCommandEnabled; }
    public int activationRadius() { return activationRadius; }
    public List<String> content() { return content; }
    public CorruptedStyle corruptedStyle() { return corruptedStyle; }

    /** The text background for {@code spawner} as ARGB. */
    public int background(Spawner spawner) {
        return spawner != null && spawner.corrupted() && corruptedStyle.enabled()
                ? corruptedStyle.background() : DEFAULT_BACKGROUND;
    }

    /** The glitch frame shown now; constant when the style is not animated. */
    public long currentFrame() {
        if (!corruptedStyle.animated()) return 0L;
        return System.currentTimeMillis() / (50L * corruptedStyle.animationTicks());
    }

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
    public List<String> lines(Spawner spawner, Map<String, String> extra, long maximumItems, int maximumXp) {
        return lines(spawner, extra, maximumItems, maximumXp, currentFrame());
    }

    /** As {@link #lines(Spawner, Map, long, int)}, rendering glitch frame {@code frame}. */
    public List<String> lines(Spawner spawner, Map<String, String> extra, long maximumItems, int maximumXp, long frame) {
        boolean corrupted = spawner != null && spawner.corrupted();
        List<String> source = corrupted && corruptedStyle.enabled() ? corruptedStyle.content() : content;
        List<String> out = new ArrayList<>(source.size());
        for (String line : source) out.add(bareHex(resolve(line, spawner, extra, maximumItems, maximumXp, frame)));
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

    private String resolve(String line, Spawner spawner, Map<String, String> extra, long maximumItems, int maximumXp,
                           long frame) {
        String result = line;

        int stack = spawner == null ? 1 : spawner.stack();
        String product = spawner == null ? "SPAWNER" : spawner.product();

        result = result.replace("%stack%", String.valueOf(stack));
        result = result.replace("%spawner_type_cap%", product.toUpperCase(Locale.ROOT));
        if (result.contains("%spawner_type_small%")) {
            result = result.replace("%spawner_type_small%", dev.yanianz.bravenspawner.util.Text.smallCaps(product.replace('_', ' ')));
        }
        result = result.replace("%spawner_type%", product);
        result = result.replace("%color%", colorFor(product));
        result = result.replace("%storage_bar%", bar(spawner == null ? 0 : ratio(spawner.storedCount(), maximumItems)));
        result = result.replace("%xp_bar%", bar(spawner == null ? 0 : ratio(spawner.storedXp(), maximumXp)));

        boolean corrupted = spawner != null && spawner.corrupted();
        SplittableRandom random = new SplittableRandom(seed(spawner, frame));
        if (result.contains("%corruption_status%")) {
            result = result.replace("%corruption_status%",
                    corrupted ? corruptedColor + corruptedText + marks(random, 3) : "");
        }
        if (result.contains("%corruption_pulse%")) result = result.replace("%corruption_pulse%", pulse(frame));
        if (result.contains("%glitch_type%")) {
            String name = dev.yanianz.bravenspawner.util.Text.smallCaps(product.replace('_', ' '));
            result = result.replace("%glitch_type%", corrupted ? glitch(name, random, frame) : colorFor(product) + "&l" + name);
        }

        if (extra != null) {
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                result = result.replace("%" + entry.getKey() + "%", entry.getValue());
            }
        }
        return result;
    }

    private static long seed(Spawner spawner, long frame) {
        long id = spawner == null || spawner.id() == null ? 0L : spawner.id().getLeastSignificantBits();
        return id * 0x9E3779B97F4A7C15L + frame;
    }

    /** Pulse colour for {@code frame}. */
    String pulse(long frame) {
        List<String> colors = corruptedStyle.pulseColors();
        return colors.get((int) Math.floorMod(frame, (long) colors.size()));
    }

    /** Up to {@code max} combining marks from {@code corrupted-chars}. */
    private String marks(SplittableRandom random, int max) {
        if (corruptedMarks.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int count = 1 + random.nextInt(max);
        for (int i = 0; i < count; i++) sb.appendCodePoint(corruptedMarks[random.nextInt(corruptedMarks.length)]);
        return sb.toString();
    }

    /**
     * {@code name} in the corrupted colour with a few letters swapped for glitch
     * glyphs in the frame's pulse colour, and some letters smeared with combining marks.
     */
    String glitch(String name, SplittableRandom random, long frame) {
        String base = corruptedColor + "&l";
        String accentColor = pulse(frame + 2);
        if (accentColor.equalsIgnoreCase(corruptedColor)) accentColor = pulse(frame + 3);
        String accent = accentColor + "&l";
        StringBuilder sb = new StringBuilder(name.length() * 4).append(base);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i) == '_' ? ' ' : name.charAt(i);
            if (c != ' ' && random.nextInt(100) < 18) {
                sb.append(accent).append(GLITCH_GLYPHS.charAt(random.nextInt(GLITCH_GLYPHS.length()))).append(base);
            } else {
                sb.append(c);
            }
            if (c != ' ' && corruptedMarks.length > 0 && random.nextInt(100) < 20) {
                sb.appendCodePoint(corruptedMarks[random.nextInt(corruptedMarks.length)]);
            }
        }
        return sb.toString();
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
