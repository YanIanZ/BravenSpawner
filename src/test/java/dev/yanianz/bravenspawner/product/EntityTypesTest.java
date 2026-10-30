package dev.yanianz.bravenspawner.product;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class EntityTypesTest {

    @Test void candidatesNormaliseNamespaceCaseAndAliases() {
        assertEquals(List.of("MOOSHROOM", "MUSHROOM_COW"), EntityTypes.entityCandidates(" minecraft:mooshroom "));
        assertEquals(List.of("MUSHROOM_COW", "MOOSHROOM"), EntityTypes.entityCandidates("mushroom_cow"));
        assertEquals(List.of("SNOWMAN", "SNOW_GOLEM"), EntityTypes.entityCandidates("snowman"));
        assertEquals(List.of("ZOMBIE"), EntityTypes.entityCandidates("Zombie"));
        assertEquals(List.of("SCUTE", "TURTLE_SCUTE"), EntityTypes.materialCandidates("minecraft:scute"));
    }

    @Test void invalidNamesYieldNoCandidates() {
        assertTrue(EntityTypes.entityCandidates(null).isEmpty());
        assertTrue(EntityTypes.entityCandidates("   ").isEmpty());
        assertTrue(EntityTypes.entityCandidates("zom$bie").isEmpty());
        assertTrue(EntityTypes.entityCandidates("minecraft:").isEmpty());
    }

    @Test void resolvesAliasesAndReturnsNullForUnknownTypesWithoutThrowing() {
        assertEquals(EntityType.MOOSHROOM, EntityTypes.entity("MUSHROOM_COW"));
        assertEquals(EntityType.SNOW_GOLEM, EntityTypes.entity("minecraft:snowman"));
        assertEquals(EntityType.ZOMBIFIED_PIGLIN, EntityTypes.entity("PIG_ZOMBIE"));
        assertEquals(EntityType.ZOMBIE, EntityTypes.entity("zombie"));
        // Newer than the 1.21.1 API on the test classpath, or simply bogus.
        assertNull(EntityTypes.entity("SULFUR_CUBE"));
        assertNull(EntityTypes.entity("NOT_A_MOB"));
        assertNull(EntityTypes.entity(null));
        assertNull(EntityTypes.entity("bad name!"));
    }

    @Test void unspawnableTypesAreRejected() {
        assertNull(EntityTypes.entity("PLAYER"));
        assertNull(EntityTypes.entity("UNKNOWN"));
    }

    @Test void defaultMobListMatchesBundledResources() throws Exception {
        Set<String> bundled;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/mobs"))) {
            bundled = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".yml"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(bundled, new TreeSet<>(ProductLoader.DEFAULT_MOBS));
        assertEquals(ProductLoader.DEFAULT_MOBS.size(), Set.copyOf(ProductLoader.DEFAULT_MOBS).size());
    }

    @Test void everyBundledMobFileIsWellFormed() throws Exception {
        for (String id : ProductLoader.DEFAULT_MOBS) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new File("src/main/resources/mobs/" + id + ".yml"));
            String type = yaml.getString("entity-type");
            assertFalse(EntityTypes.entityCandidates(type).isEmpty(), id + " entity-type");
            assertNotNull(yaml.getString("display-name"), id + " display-name");
            var drops = yaml.getConfigurationSection("drops");
            assertNotNull(drops, id + " drops");
            for (String key : drops.getKeys(false)) {
                var drop = drops.getConfigurationSection(key);
                assertFalse(EntityTypes.materialCandidates(drop.getString("material")).isEmpty(), id + " drop " + key);
                double chance = drop.getDouble("chance", 100.0);
                assertTrue(chance > 0.0 && chance <= 100.0, id + " drop " + key + " chance");
            }
            String texture = yaml.getString("head.head-texture", "");
            if (!texture.isEmpty()) {
                assertTrue(texture.matches("https://textures\\.minecraft\\.net/texture/[0-9a-f]{40,}"), id + " texture");
            }
        }
    }
}
