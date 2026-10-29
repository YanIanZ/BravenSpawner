package dev.yanianz.bravemspawner.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Colour handling and the compact money formatter (1K / 2M / 3B…). */
public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Text() {}

    public static Component of(String legacy) {
        return LEGACY.deserialize(legacy == null ? "" : legacy);
    }

    public static List<Component> of(List<String> legacy) {
        List<Component> out = new ArrayList<>(legacy == null ? 0 : legacy.size());
        if (legacy != null) {
            for (String line : legacy) out.add(of(line));
        }
        return out;
    }

    public static String strip(String legacy) {
        return legacy == null ? "" : legacy.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }

    /**
     * Compacts a number into K/M/B/T/Q/Qt. Keys are powers of 1000.
     *
     * <p>Deliberately integer-safe: money is a double, but a value like 999_999.9
     * must not render as "1000.0K" — it is floored to the next bucket boundary
     * before formatting.
     */
    public static String money(double amount, boolean enabled, boolean applyOnAmount, List<String> formats) {
        if (!enabled) return String.format(Locale.ROOT, "%.2f", amount);
        if (amount < 1000 || formats.isEmpty()) {
            return applyOnAmount
                    ? String.format(Locale.ROOT, "%.2f", amount)
                    : String.format(Locale.ROOT, "%.0f", amount);
        }
        int power = (int) Math.floor(Math.log10(amount) / 3);
        power = Math.max(1, Math.min(power, formats.size()));
        double scaled = amount / Math.pow(1000, power);
        String pattern = formats.get(power - 1);
        String number = scaled >= 100
                ? String.format(Locale.ROOT, "%.0f", scaled)
                : String.format(Locale.ROOT, "%.2f", scaled);
        return pattern.replace("%number%", number);
    }
}
