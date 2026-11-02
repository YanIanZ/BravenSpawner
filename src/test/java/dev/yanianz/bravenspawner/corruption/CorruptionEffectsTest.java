package dev.yanianz.bravenspawner.corruption;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorruptionEffectsTest {

    @Test void intervalsRoundUpToTheTickerStep() {
        assertTrue(CorruptionEffects.due(10, 10));
        assertFalse(CorruptionEffects.due(15, 10));
        assertTrue(CorruptionEffects.due(20, 10));
        // 7 ticks cannot be hit by a 5-tick ticker; it runs every 10.
        assertFalse(CorruptionEffects.due(5, 7));
        assertTrue(CorruptionEffects.due(10, 7));
        assertTrue(CorruptionEffects.due(5, 1), "never faster than the ticker");
    }
}
