package dev.yanianz.bravenspawner.product;

/**
 * Per-spawner item stock: a small reserve that refills once per production cycle
 * and caps how much a single spawner may output.
 *
 * <p>A spawner with a large virtual stack would otherwise emit its whole stack in
 * one pass. Stock turns that into a trickle: each {@link #tick()} tops the reserve
 * up by {@code restockPerCycle} (never past {@code maxStock}) and {@link #produced}
 * spends from it. This is pure arithmetic on three ints — no Bukkit types, no
 * scheduler, no world access — which is what makes the rule unit-testable without
 * a running server.
 *
 * <p>All mutation is synchronized. The cost is one uncontended monitor per cycle;
 * the benefit is that a tick can never interleave with a spend, so a concurrent
 * save or prune can read a consistent triple. The class is deliberately not
 * {@link java.util.concurrent.atomic} based: refill is a read-modify-write over
 * three related fields, and three independent atomics could expose a temporarily
 * impossible state.
 */
public final class Stock {

    /** Hard ceiling on a configured reserve; keeps arithmetic well inside int range. */
    public static final int MAX_UNITS = 1_000_000;

    private int remaining;
    private final int maxStock;
    private final int restockPerCycle;

    /**
     * Creates a full reserve.
     *
     * <p>New spawners start full so placing one produces immediately; an empty
     * reserve would make the first cycle indistinguishable from a broken product.
     */
    public Stock(int maxStock, int restockPerCycle) {
        this(clamp(maxStock), clamp(maxStock), clamp(restockPerCycle));
    }

    /**
     * Creates a reserve at a known level, used when restoring a snapshot or in a
     * test. Values outside {@code 0..MAX_UNITS} are clamped instead of rejected so
     * a corrupt saved number cannot take the spawner down with it.
     */
    public Stock(int remaining, int maxStock, int restockPerCycle) {
        this.maxStock = clamp(maxStock);
        this.restockPerCycle = clamp(restockPerCycle);
        this.remaining = Math.min(clamp(remaining), this.maxStock);
    }

    /**
     * Advances one production cycle and reports the reserve after the refill.
     *
     * <p>The return value is the current ceiling for this cycle, not a grant:
     * callers pass their requested amount to {@link #produced(int)}, which clamps
     * and spends. Returning the level rather than the difference keeps the two
     * steps independent, so a caller that produces nothing does not lose refill.
     */
    public synchronized int tick() {
        long next = (long) remaining + restockPerCycle;
        remaining = next > maxStock ? maxStock : (int) next;
        return remaining;
    }

    /**
     * Spends up to {@code requested} units and returns how many were granted.
     *
     * <p>Partial grants are the point: a spawner may still emit what the reserve
     * holds rather than all-or-nothing, and the caller forwards the granted number
     * as the real output. A negative request is treated as zero.
     */
    public synchronized int produced(int requested) {
        if (requested <= 0) return 0;
        int granted = Math.min(requested, remaining);
        remaining -= granted;
        return granted;
    }

    public synchronized int remaining() { return remaining; }

    public int maxStock() { return maxStock; }

    public int restockPerCycle() { return restockPerCycle; }

    /**
     * True when nothing can be produced until the next refill.
     *
     * <p>A {@code restockPerCycle} of zero makes this permanent after the initial
     * reserve drains; that is a deliberate "one-shot stock" configuration, not an
     * error, so it is not rejected here.
     */
    public synchronized boolean isExhausted() { return remaining <= 0; }

    private static int clamp(int value) {
        return Math.max(0, Math.min(MAX_UNITS, value));
    }
}
