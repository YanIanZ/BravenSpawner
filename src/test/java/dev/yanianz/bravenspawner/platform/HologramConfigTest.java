package dev.yanianz.bravenspawner.platform;

import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HologramConfigTest {

    private static HologramConfig bundled() throws Exception {
        try (var stream = HologramConfigTest.class.getResourceAsStream("/holograms.yml")) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return HologramConfig.from(yaml);
        }
    }

    @Test void bareHexBecomesAColourWithoutTouchingOtherForms() {
        assertEquals("&#c2c2c2(&#fff9c2 3&#969696x", HologramConfig.bareHex("#c2c2c2(#fff9c2 3#969696x"));
        assertEquals("&#abcdef <#123456> {#654321} <gradient:#111111:#222222>",
                HologramConfig.bareHex("&#abcdef <#123456> {#654321} <gradient:#111111:#222222>"));
    }

    @Test void shippedContentRendersWithoutLiteralHexCodes() throws Exception {
        Spawner spawner = mock(Spawner.class);
        when(spawner.product()).thenReturn("zombie");
        when(spawner.stack()).thenReturn(3);
        when(spawner.storedCount()).thenReturn(50L);
        when(spawner.storedXp()).thenReturn(10);
        for (String line : bundled().lines(spawner, Map.of(), 100, 100)) {
            assertFalse(Text.strip(line).matches(".*#[0-9a-fA-F]{6}.*"), line);
        }
    }

    @Test void barsUseTheSpawnerCapacity() {
        assertEquals(0.5, HologramConfig.ratio(50, 100));
        assertEquals(1.0, HologramConfig.ratio(500, 100));
        assertEquals(0.0, HologramConfig.ratio(500, 0), "unlimited capacity renders an empty bar");
    }

    @Test void shippedSwitchesAreRead() throws Exception {
        HologramConfig config = bundled();
        assertFalse(config.enabled());
        assertFalse(config.toggleCommandEnabled());
    }

    private static Spawner corrupted(boolean corrupted) {
        Spawner spawner = mock(Spawner.class);
        when(spawner.id()).thenReturn(java.util.UUID.fromString("00000000-0000-0000-0000-00000000002a"));
        when(spawner.product()).thenReturn("zombie");
        when(spawner.stack()).thenReturn(2);
        when(spawner.corrupted()).thenReturn(corrupted);
        return spawner;
    }

    @Test void corruptedSpawnerShowsTheCorruptedStyle() throws Exception {
        HologramConfig config = bundled();
        var healthy = config.lines(corrupted(false), Map.of(), 100, 100, 0);
        var broken = config.lines(corrupted(true), Map.of(), 100, 100, 0);
        assertEquals(config.content().size(), healthy.size());
        assertEquals(config.corruptedStyle().content().size(), broken.size());
        assertTrue(Text.strip(String.join("\n", broken)).contains("ᴄᴏʀʀᴜᴘᴛᴇᴅ"), broken.toString());
        for (String line : broken) {
            assertFalse(line.contains("%"), "every placeholder resolved: " + line);
            assertFalse(Text.strip(line).matches(".*#[0-9a-fA-F]{6}.*"), line);
        }
        assertEquals(HologramConfig.DEFAULT_BACKGROUND, config.background(corrupted(false)));
        assertEquals(0x662A0845, config.background(corrupted(true)));
    }

    @Test void glitchIsStableWithinAFrameAndMovesBetweenFrames() throws Exception {
        HologramConfig config = bundled();
        Spawner spawner = corrupted(true);
        assertEquals(config.lines(spawner, Map.of(), 0, 0, 7), config.lines(spawner, Map.of(), 0, 0, 7));
        java.util.Set<String> frames = new java.util.HashSet<>();
        for (long frame = 0; frame < 12; frame++) frames.add(config.lines(spawner, Map.of(), 0, 0, frame).get(0));
        assertTrue(frames.size() > 1, "the glitch changes over time");
        for (String line : frames) {
            String plain = Text.strip(line);
            assertTrue(plain.contains("SPAWNER"), plain);
            assertTrue(plain.codePoints().filter(Character::isLetter).count() >= 3, plain);
        }
    }

    @Test void disabledStyleKeepsNormalContentAndBackground() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("hologram.content", java.util.List.of("%spawner_type_cap% %corruption_status%"));
        yaml.set("hologram.corrupted-chars", "\u0336");
        yaml.set("hologram.corrupted.enabled", false);
        HologramConfig config = HologramConfig.from(yaml);
        String line = config.lines(corrupted(true), Map.of(), 0, 0, 3).get(0);
        assertTrue(line.startsWith("ZOMBIE &#8A2BE2&5&l[CORRUPTED]\u0336"), line);
        assertTrue(line.length() <= "ZOMBIE &#8A2BE2&5&l[CORRUPTED]".length() + 3, "at most three marks: " + line);
        assertEquals(HologramConfig.DEFAULT_BACKGROUND, config.background(corrupted(true)));
        assertFalse(config.corruptedStyle().animated());
    }

    @Test void backgroundAcceptsArgbAndRgb() {
        assertEquals(0x80112233, HologramConfig.argb("#80112233", 1));
        assertEquals(0x66112233, HologramConfig.argb("&#112233", 1));
        assertEquals(1, HologramConfig.argb("purple", 1));
        assertEquals(1, HologramConfig.argb(null, 1));
    }
}
