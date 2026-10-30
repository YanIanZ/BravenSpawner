package dev.yanianz.bravenspawner.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Colour handling and the compact money formatter (1K / 2M / 3B…).
 *
 * <p>Accepted input, freely mixed: legacy {@code &c}/{@code §c} codes, hex as
 * {@code &#RRGGBB}, {@code {#RRGGBB}}, {@code <#RRGGBB>} and {@code &x&R&R&G&G&B&B},
 * every MiniMessage tag, and backslash-u XXXX / backslash-U XXXXXXXX escapes. Legacy and
 * hex codes are rewritten into MiniMessage tags and the result is parsed once.
 * Text with nothing but plain legacy codes skips MiniMessage entirely, so it renders
 * exactly as it did with the plain ampersand serializer.
 */
public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer FALLBACK = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR).hexColors()
            .useUnusualXRepeatedCharacterHexFormat().build();
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** MiniMessage rejects a raw '§', so a literal one travels as this tag. */
    private static final String SECTION_TAG = "bs_section";
    private static final TagResolver LITERALS = TagResolver.resolver(SECTION_TAG,
            Tag.selfClosingInserting(Component.text(LegacyComponentSerializer.SECTION_CHAR)));
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static final String[] COLOURS = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"
    };

    private Text() {}

    public static Component of(String legacy) {
        if (legacy == null || legacy.isEmpty()) return Component.empty();
        if (isPlainLegacy(legacy)) return LEGACY.deserialize(legacy);
        try {
            return MINI.deserialize(toMiniMessage(legacy), LITERALS);
        } catch (RuntimeException malformed) {
            return FALLBACK.deserialize(legacy);
        }
    }

    public static List<Component> of(List<String> legacy) {
        List<Component> out = new ArrayList<>(legacy == null ? 0 : legacy.size());
        if (legacy != null) {
            for (String line : legacy) out.add(of(line));
        }
        return out;
    }

    /** Item name/lore: not italic unless the text asks for it. */
    public static Component item(String text) {
        return of(text).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> items(List<String> lines) {
        List<Component> out = new ArrayList<>(lines == null ? 0 : lines.size());
        if (lines != null) {
            for (String line : lines) out.add(item(line));
        }
        return out;
    }

    /** Plain text with every colour code, hex form and tag removed. */
    public static String strip(String legacy) {
        return legacy == null ? "" : PLAIN.serialize(of(legacy));
    }

    /** §-legacy string with hex as §x§R§R§G§G§B§B, for APIs that only take a String. */
    public static String legacySection(String text) {
        return SECTION.serialize(of(text));
    }

    /**
     * Makes an untrusted value (player name, item name…) render literally when it
     * is spliced into a template that later goes through {@link #of(String)}.
     */
    public static String escape(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '<' || c == '&' || c == '§' || c == '{') out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isPlainLegacy(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '\\' || c == '§' || c == '{') return false;
            if (c == '&' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '#' || next == 'x' || next == 'X') return false;
            }
        }
        return true;
    }

    /** Rewrites legacy/hex codes and unicode escapes into MiniMessage syntax. */
    static String toMiniMessage(String in) {
        StringBuilder out = new StringBuilder(in.length() + 16);
        List<String> decorations = new ArrayList<>(4);
        int n = in.length();
        int i = 0;
        while (i < n) {
            char c = in.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char d = in.charAt(i + 1);
                if (d == 'u' || d == 'U') {
                    int len = d == 'u' ? 4 : 8;
                    if (isHex(in, i + 2, len)) {
                        int cp = (int) Long.parseLong(in.substring(i + 2, i + 2 + len), 16);
                        if (Character.isValidCodePoint(cp)) {
                            appendLiteral(out, cp);
                            i += 2 + len;
                            continue;
                        }
                    }
                } else if (d == '\\' || d == '<') {
                    out.append('\\').append(d);
                    i += 2;
                    continue;
                } else if (d == '&' || d == '§' || d == '{') {
                    appendLiteral(out, d);
                    i += 2;
                    continue;
                }
                out.append("\\\\");
                i++;
                continue;
            }
            if (c == '\\') {
                out.append("\\\\");
                i++;
                continue;
            }
            if ((c == '&' || c == '§') && i + 1 < n) {
                char d = Character.toLowerCase(in.charAt(i + 1));
                if (d == '#' && isHex(in, i + 2, 6)) {
                    colour(out, decorations, "#" + in.substring(i + 2, i + 8).toLowerCase(Locale.ROOT));
                    i += 8;
                    continue;
                }
                if (d == 'x') {
                    String hex = repeatedHex(in, i + 2);
                    if (hex != null) {
                        colour(out, decorations, "#" + hex);
                        i += 14;
                        continue;
                    }
                }
                int index = "0123456789abcdef".indexOf(d);
                if (index >= 0) {
                    colour(out, decorations, COLOURS[index]);
                    i += 2;
                    continue;
                }
                String decoration = switch (d) {
                    case 'k' -> "obfuscated";
                    case 'l' -> "bold";
                    case 'm' -> "strikethrough";
                    case 'n' -> "underlined";
                    case 'o' -> "italic";
                    default -> null;
                };
                if (decoration != null) {
                    out.append('<').append(decoration).append('>');
                    decorations.add(decoration);
                    i += 2;
                    continue;
                }
                if (d == 'r') {
                    out.append("<reset>");
                    decorations.clear();
                    i += 2;
                    continue;
                }
            }
            if (c == '{' && i + 8 < n && in.charAt(i + 1) == '#' && isHex(in, i + 2, 6) && in.charAt(i + 8) == '}') {
                colour(out, decorations, "#" + in.substring(i + 2, i + 8).toLowerCase(Locale.ROOT));
                i += 9;
                continue;
            }
            if (c == '<') {
                int end = tagEnd(in, i);
                if (end < 0) {
                    out.append("\\<");
                    i++;
                } else {
                    // A closing tag or reset also ends decorations opened by legacy codes
                    // inside it; closing them first keeps the emitted tags balanced.
                    if (in.charAt(i + 1) == '/' || in.regionMatches(true, i, "<reset>", 0, 7)) {
                        closeDecorations(out, decorations);
                    }
                    out.append(in, i, end + 1);
                    i = end + 1;
                }
                continue;
            }
            appendLiteral(out, c);
            i++;
        }
        return out.toString();
    }

    /** Legacy colour semantics: a colour ends the decorations opened by legacy codes. */
    private static void colour(StringBuilder out, List<String> decorations, String tag) {
        closeDecorations(out, decorations);
        out.append('<').append(tag).append('>');
    }

    private static void closeDecorations(StringBuilder out, List<String> decorations) {
        for (int k = decorations.size() - 1; k >= 0; k--) {
            out.append("</").append(decorations.get(k)).append('>');
        }
        decorations.clear();
    }

    private static void appendLiteral(StringBuilder out, int cp) {
        if (cp == LegacyComponentSerializer.SECTION_CHAR) {
            out.append('<').append(SECTION_TAG).append('>');
            return;
        }
        if (cp == '<' || cp == '\\') out.append('\\');
        out.appendCodePoint(cp);
    }

    /**
     * Index of the {@code >} closing a tag-shaped {@code <…>}, or -1 when the
     * {@code <} is just text (e.g. {@code "<3"} or {@code "a < b"}). Tag contents are
     * copied verbatim so arguments such as gradient colours are not rewritten.
     */
    private static int tagEnd(String in, int start) {
        if (start + 1 >= in.length()) return -1;
        char first = in.charAt(start + 1);
        if (!(Character.isLetter(first) || first == '#' || first == '/' || first == '!' || first == '_')) return -1;
        char quote = 0;
        for (int k = start + 1; k < in.length(); k++) {
            char c = in.charAt(k);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '>') {
                return k;
            } else if (c == '<' || c == '\n') {
                return -1;
            }
        }
        return -1;
    }

    private static String repeatedHex(String in, int from) {
        if (from + 12 > in.length()) return null;
        StringBuilder hex = new StringBuilder(6);
        for (int k = 0; k < 6; k++) {
            char marker = in.charAt(from + k * 2);
            char digit = in.charAt(from + k * 2 + 1);
            if ((marker != '&' && marker != '§') || Character.digit(digit, 16) < 0) return null;
            hex.append(Character.toLowerCase(digit));
        }
        return hex.toString();
    }

    private static boolean isHex(String in, int from, int len) {
        if (from + len > in.length()) return false;
        for (int k = from; k < from + len; k++) {
            if (Character.digit(in.charAt(k), 16) < 0) return false;
        }
        return true;
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
