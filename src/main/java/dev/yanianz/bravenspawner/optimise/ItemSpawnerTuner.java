package dev.yanianz.bravenspawner.optimise;

/**
 * Decides when the item spawner's tile entity needs a refresh.
 *
 * <p>The {@code item_spawner_optimization} switch exists because a virtual item
 * spawner can produce every tick while its block entity's stored count changes
 * constantly. Writing the block entity on every cycle forces a chunk/block
 * update for a number no player is reading. Refreshing only when the count
 * crosses a step boundary keeps the displayed value within {@code step} of the
 * truth while cutting block writes by roughly {@code 1/step}.
 *
 * <p>Production is never gated by this class — only the tile-entity write is.
 * Counts live in the spawner model and stay exact whatever this decides.
 *
 * <p>Pure arithmetic: no Bukkit types, no server calls, safe on any thread.
 */
public final class ItemSpawnerTuner {

    /** The step used when {@code item_spawner_optimization.step} is absent or invalid. */
    public static final int DEFAULT_STEP = 64;

    private final boolean enabled;
    private final int step;

    /** Tuner at the default 64-item step. */
    public ItemSpawnerTuner(boolean enabled) {
        this(enabled, DEFAULT_STEP);
    }

    /**
     * @param enabled false makes every {@link #shouldRefresh(int, int)} call false
     * @param step    boundary width; zero, negative and one are normalised so a
     *                misconfigured value cannot refresh on every single item
     */
    public ItemSpawnerTuner(boolean enabled, int step) {
        this.enabled = enabled;
        this.step = step > 0 ? step : DEFAULT_STEP;
    }

    /**
     * True when {@code current} sits in a different step bucket than
     * {@code previous}.
     *
     * <p>Direction does not matter: gaining or losing stock across a boundary
     * both make the cached tile-entity count stale, so both refresh. A change
     * inside one bucket does not. Equal values never refresh, so a producer that
     * adds nothing in a cycle causes no block write at all.
     */
    public boolean shouldRefresh(int previous, int current) {
        if (!enabled || previous == current) return false;
        int before = Math.max(0, previous);
        int after = Math.max(0, current);
        return Math.floorDiv(before, step) != Math.floorDiv(after, step);
    }

    /** False when the optimization is off and the host should refresh every cycle. */
    public boolean enabled() {
        return enabled;
    }

    /** Effective boundary width, always positive. */
    public int step() {
        return step;
    }
}
