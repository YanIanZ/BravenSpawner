package dev.yanianz.bravenspawner.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** EX-04 / EX-07: an upgrade is never slower or smaller, and tier values are repaired. */
class TierSettingsTest {
    private static Settings settings(List<Map<String, Object>> tiers) {
        YamlConfiguration root = new YamlConfiguration();
        root.set("upgrades.tiers", tiers);
        return Settings.load(root, null, null);
    }

    private static final Settings DEFAULTS = settings(List.of(
            Map.of("name", "base", "cost", 0.0, "interval-ticks", 1200, "capacity", 45),
            Map.of("name", "iron", "cost", 5000.0, "interval-ticks", 900, "capacity", 90)));

    @Test void upgradeUsesTheFasterOfProductAndTierDelay() {
        assertEquals(200, DEFAULTS.productionPeriod(1, 200), "a fast product is not slowed by the tier");
        assertEquals(900, DEFAULTS.productionPeriod(1, 2_000), "a slow product is sped up by the tier");
        assertEquals(2_000, DEFAULTS.productionPeriod(0, 2_000), "the base tier keeps the product delay");
        assertEquals(900, DEFAULTS.productionPeriod(1, 0), "no product delay falls back to the tier");
        assertEquals(20, DEFAULTS.productionPeriod(0, 3));
    }

    @Test void tierCapacityScalesRelativeToBaseAndNeverShrinks() {
        assertEquals(1.0, DEFAULTS.capacityScale(0));
        assertEquals(2.0, DEFAULTS.capacityScale(1));
        Settings shrinking = settings(List.of(
                Map.of("name", "base", "capacity", 90), Map.of("name", "small", "capacity", 45)));
        assertEquals(1.0, shrinking.capacityScale(1));
    }

    @Test void invalidTierValuesAreRepaired() {
        Settings repaired = settings(List.of(
                Map.of("name", "bad", "cost", -500.0, "interval-ticks", 5, "capacity", 0)));
        Settings.Tier tier = repaired.tiers.get(0);
        assertEquals(0.0, tier.cost);
        assertEquals(20L, tier.intervalTicks);
        assertEquals(1, tier.capacity);
    }
}
