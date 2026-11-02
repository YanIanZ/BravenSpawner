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

    /** Each product keeps its own speed; a tier multiplies it (derived from interval-ticks here). */
    @Test void tiersScaleEachProductsOwnSpeed() {
        assertEquals(2_000, DEFAULTS.productionPeriod(0, 2_000), "the base tier keeps the product delay");
        assertEquals(1_500, DEFAULTS.productionPeriod(1, 2_000), "iron: 1200/900 = 1.33x faster");
        assertEquals(150, DEFAULTS.productionPeriod(1, 200), "a fast product still gets faster");
        assertEquals(900, DEFAULTS.productionPeriod(1, 1_200), "a 60 s product keeps its old 45 s timing");
        assertEquals(900, DEFAULTS.productionPeriod(1, 0), "no product delay uses the first tier's interval");
        assertEquals(20, DEFAULTS.productionPeriod(0, 3));
    }

    @Test void explicitSpeedWinsAndNeverSlowsDown() {
        Settings explicit = settings(List.of(
                Map.of("name", "base", "speed", 1.0, "capacity", 45),
                Map.of("name", "iron", "speed", 1.5, "capacity", 90),
                Map.of("name", "broken", "speed", 0.5, "capacity", 90)));
        assertEquals(400, explicit.productionPeriod(1, 600));
        assertEquals(1.0, explicit.speedMultiplier(2), "a tier slower than base is held at 1x");
        assertEquals(600, explicit.productionPeriod(2, 600));
        Settings clamped = settings(List.of(Map.of("name", "base"), Map.of("name", "warp", "speed", 500.0)));
        assertEquals(20.0, clamped.tiers.get(1).speed);
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
