package dev.yanianz.bravenspawner.restrictions;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class RestrictionRulesTest {
    @Test void sellOnlyModeAllowsOpeningButBlocksRawCollectionOnlyForListedProducts() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("settings.lockmode", false);
        config.set("settings.allow-take-items", false);
        config.set("restricted", List.of("zombie"));
        var rules = RestrictionRules.from(config);
        assertTrue(rules.canOpen("ZOMBIE", false));
        assertFalse(rules.canTakeItems("ZOMBIE", false));
        assertTrue(rules.canTakeItems("skeleton", false));
        assertTrue(rules.canTakeItems("zombie", true));
    }
    @Test void lockModeBlocksAllGuiAccessAndExplicitCollectionSwitchCanAllowItems() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("settings.lockmode", true);
        config.set("settings.allow-take-items", true);
        config.set("restricted", List.of("zombie"));
        var rules = RestrictionRules.from(config);
        assertFalse(rules.canOpen("zombie", false));
        assertTrue(rules.canOpen("zombie", true));
        assertTrue(rules.canTakeItems("zombie", false));
    }
}
