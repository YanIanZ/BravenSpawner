package dev.yanianz.bravenspawner.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextTest {

    /** One rendered code point with its effective colour and enabled decorations. */
    private record Glyph(String text, TextColor color, Set<TextDecoration> decorations) {}

    private static List<Glyph> flatten(Component component) {
        List<Glyph> out = new ArrayList<>();
        walk(component, null, EnumSet.noneOf(TextDecoration.class), out);
        return out;
    }

    private static void walk(Component c, TextColor parentColor, EnumSet<TextDecoration> inherited, List<Glyph> out) {
        TextColor color = c.color() != null ? c.color() : parentColor;
        EnumSet<TextDecoration> enabled = inherited.clone();
        for (TextDecoration decoration : TextDecoration.values()) {
            TextDecoration.State state = c.decoration(decoration);
            if (state == TextDecoration.State.TRUE) enabled.add(decoration);
            else if (state == TextDecoration.State.FALSE) enabled.remove(decoration);
        }
        if (c instanceof TextComponent text) {
            text.content().codePoints().forEach(cp ->
                    out.add(new Glyph(new String(Character.toChars(cp)), color, enabled.clone())));
        }
        for (Component child : c.children()) walk(child, color, enabled, out);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static TextColor colorAt(Component component, int index) {
        return flatten(component).get(index).color();
    }

    private static final TextColor HEX = TextColor.color(0x12ABEF);

    @Test
    void legacyCodesRenderLikeTheAmpersandSerializer() {
        List<String> samples = List.of(
                "&aGreen", "&c&lBold red &rplain", "&lBold &cRed not bold", "&7Gray &8dark &fwhite",
                "&kx&mstrike&nunder&oital", "&cA&r&eB", "plain text", "& alone & not code", "&C upper &L bold",
                "&6Gold &l&nBoth &9blue");
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
        for (String sample : samples) {
            List<Glyph> expected = flatten(legacy.deserialize(sample));
            assertEquals(expected, flatten(Text.of(sample)), "fast path: " + sample);
            assertEquals(expected, flatten(MiniMessage.miniMessage().deserialize(Text.toMiniMessage(sample))),
                    "minimessage path: " + sample);
            if (!sample.contains("& ")) {
                assertEquals(expected, flatten(Text.of(sample.replace('&', '§'))), "section: " + sample);
            }
        }
    }

    @Test
    void everyHexFormResolvesToTheSameColour() {
        for (String form : List.of("&#12abef", "&#12ABEF", "{#12abef}", "<#12abef>", "<color:#12abef>",
                "&x&1&2&a&b&e&f", "§x§1§2§a§b§e§f", "§#12abef")) {
            Component c = Text.of(form + "Hi");
            assertEquals("Hi", plain(c), form);
            assertEquals(HEX, colorAt(c, 0), form);
        }
    }

    @Test
    void bareHashIsNotAColour() {
        Component c = Text.of("#12abef text");
        assertEquals("#12abef text", plain(c));
        assertEquals(null, colorAt(c, 0));
    }

    @Test
    void hexColourEndsLegacyDecorationsLikeANamedColour() {
        List<Glyph> glyphs = flatten(Text.of("&lA&#12abefB"));
        assertTrue(glyphs.get(0).decorations().contains(TextDecoration.BOLD));
        assertEquals(Set.of(), glyphs.get(1).decorations());
        assertEquals(HEX, glyphs.get(1).color());
    }

    @Test
    void miniMessageTagsWork() {
        assertEquals(NamedTextColor.RED, colorAt(Text.of("<red>x"), 0));
        assertTrue(flatten(Text.of("<bold>x</bold>")).get(0).decorations().contains(TextDecoration.BOLD));
        Component gradient = Text.of("<gradient:#ff0000:#0000ff>abcdef</gradient>");
        assertEquals("abcdef", plain(gradient));
        List<Glyph> glyphs = flatten(gradient);
        assertEquals(TextColor.color(0xFF0000), glyphs.get(0).color());
        assertNotEquals(glyphs.get(0).color(), glyphs.get(5).color());
        Component rainbow = Text.of("<rainbow>abcd</rainbow>");
        assertEquals("abcd", plain(rainbow));
        assertNotEquals(colorAt(rainbow, 0), colorAt(rainbow, 2));
    }

    @Test
    void legacyAndMiniMessageMix() {
        Component c = Text.of("&7Hello <gradient:#ff0000:#00ff00>&lworld</gradient> &cdone");
        assertEquals("Hello world done", plain(c));
        List<Glyph> glyphs = flatten(c);
        assertEquals(NamedTextColor.GRAY, glyphs.get(0).color());
        assertTrue(glyphs.get(6).decorations().contains(TextDecoration.BOLD));
        assertEquals(NamedTextColor.RED, glyphs.get(glyphs.size() - 1).color());
    }

    @Test
    void unicodeEscapesBecomeCodePoints() {
        assertEquals("❤ love", plain(Text.of("\\u2764 love")));
        assertEquals("🔥 hot", plain(Text.of("\\U0001F525 hot")));
        assertEquals("🔥", plain(Text.of("\\uD83D\\uDD25")));
        assertEquals("<b>", plain(Text.of("\\u003Cb>")));
        Component colored = Text.of("&c\\U0001F525");
        assertEquals("🔥", plain(colored));
        assertEquals(NamedTextColor.RED, colorAt(colored, 0));
    }

    @Test
    void invalidEscapesStayLiteral() {
        assertEquals("\\uZZZZ", plain(Text.of("\\uZZZZ")));
        assertEquals("\\U00110000", plain(Text.of("\\U00110000")));
        assertEquals("C:\\path", plain(Text.of("C:\\path")));
        assertEquals("end\\", plain(Text.of("end\\")));
    }

    @Test
    void rawUtf8Survives() {
        assertEquals("✦ Spawner ✦ 🔥 ñ 漢字", plain(Text.of("&6✦ Spawner ✦ 🔥 ñ 漢字")));
        assertEquals("✦ x", plain(Text.of("<gold>✦ x")));
    }

    @Test
    void stripRemovesEverything() {
        assertEquals("Hello world", Text.strip("&a&lHello &#12abefwo{#ffffff}r<red>l</red>&x&1&2&3&4&5&6d"));
        assertEquals("Grad", Text.strip("<gradient:#ff0000:#00ff00>Grad</gradient>"));
        assertEquals("plain", Text.strip("§cplain"));
        assertEquals("", Text.strip(null));
        assertEquals("", Text.strip("&7"));
    }

    @Test
    void malformedInputNeverThrows() {
        List<String> nasty = List.of("<gradient:#zzz>hi", "<red", "</bold>x", "<click:run_command>x", "<3 love",
                "a < b > c", "<<<>>>", "<", ">", "&", "&#12", "&x&1&2", "{#12}", "{#12abef", "<hover:show_text:'x",
                "<#gg0000>x", "\\", "\\u", "<reset>", "<rainbow:!!!>x", "<transition:#f00>x");
        for (String text : nasty) {
            assertDoesNotThrow(() -> Text.of(text), text);
            assertDoesNotThrow(() -> Text.strip(text), text);
            assertDoesNotThrow(() -> Text.legacySection(text), text);
        }
        assertEquals("<3 love", plain(Text.of("<3 love")));
        assertEquals("a < b > c", plain(Text.of("a < b > c")));
        assertEquals("&#12 {#12}", plain(Text.of("&#12 {#12}")));
    }

    @Test
    void unknownTagsStayLiteral() {
        Component usage = Text.of("&7/bmodify <player> while looking at the spawner");
        assertEquals("/bmodify <player> while looking at the spawner", plain(usage));
        assertEquals(NamedTextColor.GRAY, colorAt(usage, 0));
        assertEquals("/bgive <player> <amount> [type]", Text.strip("&e/bgive <player> <amount> [type]"));
        assertEquals("> Admin Query Log <", Text.strip("&c> &8Admin Query Log &c<"));
    }

    @Test
    void escapeNeutralisesInjectedFormatting() {
        String name = "<red>&cBob{#ff0000}\\u0041§l<click:run_command:/op>";
        String escaped = Text.escape(name);
        Component c = Text.of("&7Player: &f" + escaped);
        assertEquals("Player: " + name, plain(c));
        for (Glyph glyph : flatten(c).subList(8, 8 + name.length())) {
            assertEquals(NamedTextColor.WHITE, glyph.color());
            assertEquals(Set.of(), glyph.decorations());
        }
        assertEquals("Steve_123", Text.escape("Steve_123"));
        assertEquals("", Text.escape(null));
    }

    @Test
    void itemTextIsNotItalicUnlessAsked() {
        assertEquals(TextDecoration.State.FALSE, Text.item("&aName").decoration(TextDecoration.ITALIC));
        assertTrue(flatten(Text.item("&oItalic")).get(0).decorations().contains(TextDecoration.ITALIC));
        assertTrue(flatten(Text.item("<italic>Italic")).get(0).decorations().contains(TextDecoration.ITALIC));
        assertTrue(flatten(Text.item("<i>Italic")).get(0).decorations().contains(TextDecoration.ITALIC));
        List<Component> lore = Text.items(List.of("&7one", "<gray>two"));
        assertEquals(2, lore.size());
        for (Component line : lore) {
            assertEquals(Set.of(), flatten(line).get(0).decorations());
            assertEquals(TextDecoration.State.FALSE, line.decoration(TextDecoration.ITALIC));
        }
        assertEquals(List.of(), Text.items(null));
    }

    @Test
    void legacySectionKeepsHex() {
        assertEquals("§x§1§2§a§b§e§fHi", Text.legacySection("&#12abefHi"));
        assertEquals("§cRed", Text.legacySection("&cRed"));
    }

    @Test
    void nullAndEmpty() {
        assertEquals("", plain(Text.of((String) null)));
        assertEquals("", plain(Text.of("")));
        assertEquals(List.of(), Text.of((List<String>) null));
    }

    @Test
    void moneyFormattingUnchanged() {
        List<String> formats = List.of("%number%K", "%number%M");
        assertEquals("1.50K", Text.money(1500, true, false, formats));
        assertEquals("250K", Text.money(250_000, true, false, formats));
        assertEquals("12.00", Text.money(12, false, false, formats));
    }
}
