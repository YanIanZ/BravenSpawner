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
}
