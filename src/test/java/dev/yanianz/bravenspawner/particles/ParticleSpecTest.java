package dev.yanianz.bravenspawner.particles;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ParticleSpecTest {

    private static final ParticleSpec.Defaults DEFAULTS =
            new ParticleSpec.Defaults(true, "FLAME", 5, 0.3, 0.0, 16.0, "#FFFFFF", 1.0f);

    @Test void legacyAndCurrentNamesResolve() {
        assertEquals(Particle.DUST, ParticleSpec.resolve("REDSTONE"));
        assertEquals(Particle.DUST, ParticleSpec.resolve("dust"));
        assertEquals(Particle.HAPPY_VILLAGER, ParticleSpec.resolve("VILLAGER_HAPPY"));
        assertEquals(Particle.FLAME, ParticleSpec.resolve("minecraft:flame"));
        assertEquals(Particle.ENTITY_EFFECT, ParticleSpec.resolve("SPELL_MOB"));
    }

    @Test void coloursParseFromHexAndNames() {
        assertEquals(Color.fromRGB(0x7EE787), ParticleSpec.color("#7EE787"));
        assertEquals(Color.fromRGB(0x7EE787), ParticleSpec.color("7ee787"));
        assertEquals(Color.RED, ParticleSpec.color("red"));
        assertNull(ParticleSpec.color("#12"));
    }

    @Test void dustGetsColouredOptionsAndPlainParticlesNoData() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("type", "REDSTONE");
        yaml.set("color", "#FF0000");
        yaml.set("count", 9999);
        ParticleSpec dust = ParticleSpec.read(yaml, "test", DEFAULTS, message -> { });
        assertNotNull(dust);
        Particle.DustOptions options = assertInstanceOf(Particle.DustOptions.class, dust.data());
        assertEquals(Color.RED, options.getColor());
        assertEquals(200, dust.count(), "count is clamped");

        ParticleSpec flame = ParticleSpec.read(null, "test", DEFAULTS, message -> { });
        assertNotNull(flame);
        assertNull(flame.data());
        assertEquals(5, flame.count());
    }

    @Test void unknownOrDisabledEffectsAreSkippedWithOneWarning() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("type", "NOT_A_PARTICLE");
        List<String> warnings = new ArrayList<>();
        assertNull(ParticleSpec.read(yaml, "particles.spawner", DEFAULTS, warnings::add));
        assertEquals(1, warnings.size());
        yaml.set("type", "FLAME");
        yaml.set("enabled", false);
        assertNull(ParticleSpec.read(yaml, "particles.spawner", DEFAULTS, warnings::add));
        assertEquals(1, warnings.size());
    }
}
