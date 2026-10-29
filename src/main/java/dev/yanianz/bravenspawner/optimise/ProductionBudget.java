package dev.yanianz.bravenspawner.optimise;

/**
 * Per-pass ceiling on how many items one production sweep may create.
 *
 * <p>Without a budget, a region with many large item spawners can produce an
 * unbounded number of stacks in a single tick, which is the usual cause of a
 * server-wide stall after a stack-limit increase. The budget is consumed as each
 * spawner produces and is reset once at the start of every pass, so one greedy
 * spawner cannot starve the rest forever — it simply waits for the next pass.
 *
 * <p>Consumption is all-or-nothing: a request larger than what remains is
 * refused without spending the remainder, so a caller can try a smaller batch
 * (for example one item) and still make progress instead of leaving the budget
 * permanently unusable.
 *
 * <p>One instance belongs to one production thread. The pass runs on the region
 * that owns the spawners, so no synchronisation is needed; sharing an instance
 * across regions would need a lock and is not supported.
 */
public final class ProductionBudget {

    /** The ceiling used when the host does not name one. */
    public static final int DEFAULT_LIMIT = 256;

    private final int limit;
    private int remaining;

    /** Budget at the default 256 produced items per pass. */
    public ProductionBudget() {
        this(DEFAULT_LIMIT);
    }

    /**
     * @param limit maximum items per pass; zero or negative falls back to
     *              {@link #DEFAULT_LIMIT} so a bad config cannot silently stop
     *              all production
     */
    public ProductionBudget(int limit) {
        this.limit = limit > 0 ? limit : DEFAULT_LIMIT;
        this.remaining = this.limit;
    }

    /**
     * Reserves {@code n} items from the current pass.
     *
     * @return true and the reservation stands, or false with nothing consumed
     *         when {@code n} exceeds {@link #remaining()}
     */
    public boolean tryConsume(int n) {
        if (n <= 0) return true;
        if (n > remaining) return false;
        remaining -= n;
        return true;
    }

    /** Starts a new pass; call once before the first spawner is produced. */
    public void reset() {
        remaining = limit;
    }

    /** Items still producible in this pass. */
    public int remaining() {
        return remaining;
    }

    /** Ceiling this budget was built with. */
    public int limit() {
        return limit;
    }

    /** True when no further positive request can succeed until {@link #reset()}. */
    public boolean exhausted() {
        return remaining <= 0;
    }
}
