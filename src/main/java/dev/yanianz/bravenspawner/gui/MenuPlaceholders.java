package dev.yanianz.bravenspawner.gui;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code %placeholder%} values every spawner menu can use.
 *
 * <p>Values are computed from a plain {@link Snapshot} so the token set can be
 * tested without a server: a test asserts that every token in the bundled
 * {@code gui/*.yml} files is in {@link #KNOWN}, and that {@link #build} fills
 * every known token except the per-line booster ones.
 */
public final class MenuPlaceholders {

    /** Tokens only valid inside {@code special.booster_display}, filled once per active booster. */
    public static final Set<String> BOOSTER_LINE = Set.of("booster_name", "booster_multiplier", "remaining_time");

    /** Every token a bundled menu may use. */
    public static final Set<String> KNOWN = Set.of(
            "entity", "displayname", "color", "tier", "stack", "max_stack", "stored", "xp", "value",
            "page", "pages", "head_texture", "head_material", "price", "total_price", "spawner_price",
            "speed", "drop_info", "xp_info", "storage_value", "storage_price", "xp_value",
            "player", "logtype", "limit", "time",
            "booster_name", "booster_multiplier", "remaining_time");

    /**
     * Inputs for one render.
     *
     * @param minTicks    shortest production period in ticks, after boosters
     * @param maxTicks    longest production period in ticks, after boosters
     * @param maxItems    item capacity; 0 or less is unlimited
     * @param maxXp       XP capacity; 0 or less is unlimited
     * @param sellValue   formatted sale value of the current storage
     * @param unitPrice   formatted price of one spawner
     * @param batchPrice  formatted price of the next batch of up to 64 spawners
     * @param headTexture the product's {@code head.head-texture} (URL, hash or base64); blank when none
     * @param headMaterial material name of the product's head icon; blank keeps the menu's material
     */
    public record Snapshot(String product, String displayName, String color, int tier, int stack, int maxStack,
                           long stored, long maxItems, int xp, int maxXp, String sellValue,
                           String unitPrice, String batchPrice,
                           long minTicks, long maxTicks, int dropMin, int dropMax, int xpMin, int xpMax,
                           int page, int pages, String headTexture, String headMaterial) { }

    private MenuPlaceholders() {}

    public static Map<String, String> build(Snapshot s) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("entity", s.displayName());
        values.put("displayname", s.displayName());
        values.put("color", s.color());
        values.put("tier", String.valueOf(s.tier()));
        values.put("stack", number(s.stack()));
        values.put("max_stack", number(s.maxStack()));
        values.put("stored", number(s.stored()));
        values.put("xp", number(s.xp()));
        values.put("value", s.sellValue());
        values.put("page", String.valueOf(s.page() + 1));
        values.put("pages", String.valueOf(s.pages()));
        values.put("head_texture", s.headTexture() == null ? "" : s.headTexture().trim());
        values.put("head_material", s.headMaterial() == null ? "" : s.headMaterial());
        values.put("price", s.unitPrice());
        values.put("spawner_price", s.unitPrice());
        values.put("total_price", s.batchPrice());
        values.put("speed", range(seconds(s.minTicks()), seconds(s.maxTicks())));
        values.put("drop_info", range(number(s.dropMin()), number(s.dropMax())));
        values.put("xp_info", range(number(s.xpMin()), number(s.xpMax())));
        values.put("storage_value", capacity(s.stored(), s.maxItems()));
        values.put("storage_price", s.sellValue());
        values.put("xp_value", capacity(s.xp(), s.maxXp()));
        values.put("player", "All");
        values.put("logtype", "All");
        values.put("limit", "64");
        values.put("time", "All time");
        return values;
    }

    static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    static String seconds(long ticks) {
        double seconds = Math.max(0, ticks) / 20.0;
        return seconds == Math.rint(seconds)
                ? String.valueOf((long) seconds)
                : String.format(Locale.ROOT, "%.1f", seconds);
    }

    private static String range(String low, String high) {
        return low.equals(high) ? low : low + "-" + high;
    }

    private static String capacity(long value, long maximum) {
        return maximum > 0 ? number(value) + "/" + number(maximum) : number(value);
    }
}
