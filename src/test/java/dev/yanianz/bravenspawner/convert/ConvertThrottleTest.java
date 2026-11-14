package dev.yanianz.bravenspawner.convert;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Server-wide per-tick conversion budget. */
class ConvertThrottleTest {

    @Test void budgetRefillsOnTheNextTick() {
        AtomicLong tick = new AtomicLong(100L);
        ConvertThrottle throttle = new ConvertThrottle(2, tick::get);
        assertTrue(throttle.tryAcquire());
        assertTrue(throttle.tryAcquire());
        assertFalse(throttle.tryAcquire(), "third conversion in the same tick is throttled");
        tick.set(101L);
        assertTrue(throttle.tryAcquire(), "a new tick refills the budget");
        assertTrue(throttle.tryAcquire());
        assertFalse(throttle.tryAcquire(), "the refilled budget is still capped");
    }

    @Test void budgetClampsToOneAtLeast() {
        AtomicLong tick = new AtomicLong(7L);
        assertEquals(1, new ConvertThrottle(0, tick::get).maxPerTick());
        assertEquals(ConvertThrottle.MAX_PER_TICK, new ConvertThrottle(10_000, tick::get).maxPerTick());
        assertTrue(new ConvertThrottle(0, tick::get).tryAcquire(), "a zero config still converts once");
    }

    @Test void brokenClockFailsClosed() {
        ConvertThrottle throttle = new ConvertThrottle(4, () -> {
            throw new IllegalStateException("no clock");
        });
        assertFalse(throttle.tryAcquire());
    }

    @Test void defaultBudgetIsFour() {
        assertEquals(4, ConvertThrottle.withDefaultBudget().maxPerTick());
    }
}
