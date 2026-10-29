package dev.yanianz.bravenspawner.menu;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BundledMenusTest {
    @Test void everyBundledMenuHasAValidDefinition() throws Exception {
        for (String name : List.of("spawner", "storage", "buy_stack", "corruption", "log")) {
            try (var stream = getClass().getResourceAsStream("/gui/" + name + ".yml")) {
                assertNotNull(stream, name);
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
                MenuDefinition definition = MenuDefinition.parse(name, yaml.getConfigurationSection("menu"), yaml.getConfigurationSection("items"));
                for (var layout : definition.layouts().values()) {
                    assertTrue(layout.size() <= definition.rows(), name + " layout exceeds inventory");
                    for (String row : layout) {
                        for (char symbol : row.toCharArray()) assertNotNull(definition.item(symbol), name + " undefined symbol " + symbol);
                    }
                }
            }
        }
    }
}
