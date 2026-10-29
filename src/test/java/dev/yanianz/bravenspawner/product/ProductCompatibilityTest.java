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
            assertEquals(40, result.mobs().size());
            assertEquals(4, result.items().size());
            assertEquals(0, result.malformed());
        }
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
        }
    }
}
