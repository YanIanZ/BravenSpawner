package dev.yanianz.bravenspawner.convert;

import java.util.function.LongSupplier;

/**
 * Server-wide conversion budget: at most N conversions per tick.
 *
 * <p>A chunk full of vanilla spawners (or a scan over hundreds of loaded
 * chunks) must not register, schedule and save hundreds of spawners in one
 * tick. Each conversion takes one budget unit; when the window is spent, the
 * remaining spawners stay vanilla and are reported as throttled. Chunk-load
 * leftovers are retried by a bounded delayed task; scan leftovers are picked
 * up by re-running the scan.
 *
 * <p>A tick here is a 50 ms wall-clock window (the server tick length), not a
 * scheduler callback, so no repeating task runs forever just to reset a
 * counter. The tick source is injected for deterministic tests.
 */
public final class ConvertThrottle {

    /** Hard bounds for {@code convert.max-per-tick}; 0 would forbid every conversion. */
    public static final int MIN_PER_TICK = 1;
    public static final int MAX_PER_TICK = 64;

    private final int maxPerTick;
    private final LongSupplier tickSource;
    private long currentTick = Long.MIN_VALUE;
    private int used;

    /**
     * @param maxPerTick budget per tick, clamped to {@value #MIN_PER_TICK}..{@value #MAX_PER_TICK}
     * @param tickSource monotonically non-decreasing tick; the runtime passes
     *                   {@code System.currentTimeMillis() / 50}
     */
    public ConvertThrottle(int maxPerTick, LongSupplier tickSource) {
        this.maxPerTick = Math.max(MIN_PER_TICK, Math.min(MAX_PER_TICK, maxPerTick));
        this.tickSource = tickSource == null ? ConvertThrottle::wallTick : tickSource;
    }

    /** Server default: 4 conversions per tick on the wall clock. */
    public static ConvertThrottle withDefaultBudget() {
        return new ConvertThrottle(4, ConvertThrottle::wallTick);
    }

    /** Wall-clock tick used at runtime. */
    public static long wallTick() {
        return System.currentTimeMillis() / 50L;
    }

    public int maxPerTick() {
        return maxPerTick;
    }

    /**
     * Takes one budget unit when the current window still has room.
     *
     * @return true when the caller may convert now; false means throttled
     */
    public synchronized boolean tryAcquire() {
        long tick;
        try {
            tick = tickSource.getAsLong();
        } catch (RuntimeException broken) {
            return false;
        }
        if (tick != currentTick) {
            currentTick = tick;
            used = 0;
        }
        if (used >= maxPerTick) {
            return false;
        }
        used++;
        return true;
    }
}
