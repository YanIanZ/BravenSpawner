package dev.yanianz.bravenspawner.wand;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WandLifetimeTest {
    @Test void finiteUsesAreConsumedOnceUntilExhausted() {
        WandLifetime lifetime = WandLifetime.parse("2", 1000L);
        assertTrue(lifetime.usable(1000L));
        lifetime = lifetime.consume();
        assertEquals(1, lifetime.uses());
        assertTrue(lifetime.usable(1000L));
        lifetime = lifetime.consume();
        assertEquals(0, lifetime.uses());
        assertFalse(lifetime.usable(1000L));
        assertEquals(0, lifetime.consume().uses());
    }
    @Test void daysExpireAtTheExactBoundaryAndDoNotLoseUses() {
        WandLifetime lifetime = WandLifetime.parse("2d", 1000L);
        assertEquals(172801000L, lifetime.expiresAt());
        assertTrue(lifetime.usable(172800999L));
        assertFalse(lifetime.usable(172801000L));
        assertEquals(lifetime, lifetime.consume());
    }
    @Test void infiniteWandNeverConsumesOrExpires() {
        WandLifetime lifetime = WandLifetime.parse("inf", 1000L);
        assertTrue(lifetime.usable(Long.MAX_VALUE));
        assertEquals(lifetime, lifetime.consume());
    }
    @Test void rejectsZeroNegativeMalformedAndOverflowDurations() {
        for (String input : new String[]{"0", "-1", "0d", "-2d", "1.5d", "NaN", "999999999999999d"}) {
            assertThrows(IllegalArgumentException.class, () -> WandLifetime.parse(input, 1000L), input);
        }
    }
}
