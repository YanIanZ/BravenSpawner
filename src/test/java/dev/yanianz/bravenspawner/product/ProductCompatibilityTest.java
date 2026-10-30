package dev.yanianz.bravenspawner.product;

import org.bukkit.Material;
import org.mockito.MockedStatic;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductCompatibilityTest {
    @TempDir Path data;
    @Test void freshInstallLoadsAllBundledProductsWithoutMigratingEmbeddedDefaults() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("mobs.ZOMBIE.drops", java.util.List.of("ROTTEN_FLESH:1:1.0"));
        when(plugin.getConfig()).thenReturn(defaults);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        doAnswer(call -> {
            Path relative = Path.of(call.getArgument(0, String.class));
            Path destination = data.resolve(relative);
            Files.createDirectories(destination.getParent());
            Files.copy(Path.of("src/main/resources").resolve(relative), destination);
            return null;
        }).when(plugin).saveResource(anyString(), eq(false));
        Material material = mock(Material.class);
        when(material.isItem()).thenReturn(true);
        try (MockedStatic<Material> materials = mockStatic(Material.class)) {
            materials.when(() -> Material.matchMaterial(anyString())).thenReturn(material);
            var result = new ProductLoader(plugin, false).load();
            // The test classpath is paper-api 1.21.1: mobs added after it are
            // skipped as unavailable, never reported as malformed.
            assertEquals(java.util.Set.of("camel_husk", "copper_golem", "creaking", "happy_ghast",
                    "nautilus", "parched", "sulfur_cube", "zombie_nautilus"),
                    new java.util.HashSet<>(result.unavailable()));
            assertEquals(java.util.Set.of("ender_dragon", "giant", "illusioner", "wither"),
                    new java.util.HashSet<>(result.disabled()));
            assertEquals(ProductLoader.DEFAULT_MOBS.size() - 12, result.mobs().size());
            assertEquals(org.bukkit.entity.EntityType.MOOSHROOM, result.mobs().get("mushroom_cow").entityType());
            assertEquals(4, result.items().size());
            assertEquals(0, result.malformed());
        }
    }

    private JavaPlugin plugin(java.util.List<java.util.logging.LogRecord> log) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { log.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        when(plugin.getLogger()).thenReturn(logger);
        doAnswer(call -> {
            Path relative = Path.of(call.getArgument(0, String.class));
            Path destination = data.resolve(relative);
            Files.createDirectories(destination.getParent());
            Files.copy(Path.of("src/main/resources").resolve(relative), destination);
            return null;
        }).when(plugin).saveResource(anyString(), eq(false));
        return plugin;
    }

    private static String mob(String type, String material) {
        return "entity-type: " + type + "\ndrops:\n  1:\n    material: " + material + "\n    chance: 50.0\n";
    }

    @Test void unknownTypesDisabledProductsAndUnknownDropsAreSummarisedNotErrors() throws Exception {
        Files.createDirectories(data.resolve("mobs"));
        Files.createDirectories(data.resolve("itemspawners"));
        Files.writeString(data.resolve("mobs/.migrated"), "");
        Files.writeString(data.resolve("itemspawners/.migrated"), "");
        Files.writeString(data.resolve("mobs/future_mob.yml"), mob("FUTURE_MOB", "ROTTEN_FLESH"));
        Files.writeString(data.resolve("mobs/other_future.yml"), mob("minecraft:other_future", "ROTTEN_FLESH"));
        Files.writeString(data.resolve("mobs/boss.yml"), "enabled: false\n" + mob("WITHER", "NETHER_STAR"));
        Files.writeString(data.resolve("mobs/zombie.yml"), mob("ZOMBIE", "ROTTEN_FLESH")
                + "  2:\n    material: FUTURE_ITEM\n    chance: 10.0\n");
        Files.writeString(data.resolve("mobs/only_future_drops.yml"), mob("ZOMBIE", "FUTURE_ITEM"));
        Files.writeString(data.resolve("mobs/cow.yml"), mob("MUSHROOM_COW", "BEEF"));
        var log = new java.util.ArrayList<java.util.logging.LogRecord>();
        Material material = mock(Material.class);
        when(material.isItem()).thenReturn(true);
        try (MockedStatic<Material> materials = mockStatic(Material.class)) {
            materials.when(() -> Material.matchMaterial(anyString())).thenAnswer(call ->
                    call.getArgument(0, String.class).contains("FUTURE") ? null : material);
            var result = new ProductLoader(plugin(log), false).load();
            assertEquals(java.util.Set.of("zombie", "cow"), result.mobs().keySet());
            assertEquals(1, result.mobs().get("zombie").drops().size());
            assertEquals(org.bukkit.entity.EntityType.MOOSHROOM, result.mobs().get("cow").entityType());
            assertEquals(java.util.List.of("future_mob", "only_future_drops", "other_future"), result.unavailable());
            assertEquals(java.util.List.of("boss"), result.disabled());
            assertEquals(0, result.malformed());
        }
        assertTrue(log.stream().noneMatch(r -> r.getLevel() == java.util.logging.Level.SEVERE));
        assertTrue(log.stream().noneMatch(r -> r.getThrown() != null));
        assertEquals(1, log.stream().filter(r -> r.getMessage().startsWith("Skipped 3 product(s)")).count());
        assertEquals(1, log.stream().filter(r -> r.getLevel() == java.util.logging.Level.WARNING).count());
    }

    @Test void legacyDefaultsMarkerReceivesNewBundledMobsButNotDeletedOnes() throws Exception {
        Files.createDirectories(data.resolve("mobs"));
        Files.createDirectories(data.resolve("itemspawners"));
        Files.writeString(data.resolve("mobs/.migrated"), "catalogue initialized\n");
        Files.writeString(data.resolve("itemspawners/.migrated"), "");
        Files.writeString(data.resolve("mobs/zombie.yml"), mob("ZOMBIE", "ROTTEN_FLESH"));
        var log = new java.util.ArrayList<java.util.logging.LogRecord>();
        Material material = mock(Material.class);
        when(material.isItem()).thenReturn(true);
        try (MockedStatic<Material> materials = mockStatic(Material.class)) {
            materials.when(() -> Material.matchMaterial(anyString())).thenReturn(material);
            new ProductLoader(plugin(log), false).load();
            assertTrue(Files.exists(data.resolve("mobs/warden.yml")));
            assertTrue(Files.exists(data.resolve("mobs/sulfur_cube.yml")));
            // An owner who deleted a pre-existing default does not get it back.
            assertFalse(Files.exists(data.resolve("mobs/skeleton.yml")));
            Files.delete(data.resolve("mobs/warden.yml"));
            new ProductLoader(plugin(log), false).load();
            assertFalse(Files.exists(data.resolve("mobs/warden.yml")));
        }
        String marker = Files.readString(data.resolve("mobs/.migrated"));
        assertTrue(marker.startsWith("catalogue initialized\n"));
        assertTrue(marker.contains("\nwarden\n"));
    }

    @Test void percentageBelowOneIsStillAPercentage() throws Exception {
        Files.createDirectories(data.resolve("itemspawners"));
        Files.writeString(data.resolve("itemspawners/diamond.yml"), """
            item-type: DIAMOND
            drops:
              1:
                material: DIAMOND
                amount: {min: 1, max: 3}
                chance: 0.5
            spawn-delays: {min: 5, max: 15}
            xp: {min: 5, max: 8}
            storage: {max-items: 1000, max-xp: 2000}
            max-stack: 32
            spawner-price: 100000
            head:
              material: PLAYER_HEAD
              head-texture: https://textures.minecraft.net/texture/abc123
            """);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        Material material = mock(Material.class);
        when(material.isItem()).thenReturn(true);
        try (MockedStatic<Material> materials = mockStatic(Material.class)) {
        materials.when(() -> Material.matchMaterial("DIAMOND")).thenReturn(material);
        var result = new ProductLoader(plugin).load();
        var diamond = result.items().get("diamond");
        assertNotNull(diamond);
        assertEquals(0.005, diamond.drops().getFirst().chance(), 1e-9);
        assertEquals(5, diamond.xp());
        assertEquals(100, diamond.intervalTicks());
        assertEquals(100000, diamond.spawnerPrice());
        assertEquals("https://textures.minecraft.net/texture/abc123", diamond.headTexture());
        }
    }
}
