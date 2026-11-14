package dev.yanianz.bravenspawner.convert;

/**
 * Pure guard evaluation shared by every conversion path.
 *
 * <p>Chunk-load conversion, join inventory conversion and the admin scan all
 * answer the same question — "may this vanilla spawner become managed?" — and
 * must answer it identically. The checks run in a fixed order so a receipt can
 * blame exactly one reason: readiness first (fail closed), then the master
 * switch, then the world, then identity, then the product, then capacity, and
 * the throttle budget last so a skipped spawner never consumes budget.
 *
 * <p>This class takes precomputed booleans and has no side effects, which keeps
 * the guard matrix unit-testable without a server.
 */
public final class ConvertGuards {

    private ConvertGuards() {
    }

    /**
     * Evaluates the guard chain for one spawner.
     *
     * @param ready            storage loaded and the plugin ready
     * @param enabled          {@code convert.enabled} master switch
     * @param blacklisted      spawner's world is in {@code blacklisted-worlds}
     * @param alreadyManaged   a record already exists for this block
     * @param hasProduct       the spawner's mob resolves to a product
     * @param chunkAllows      the chunk cap still has room (including conversions
     *                         already done in this chunk pass)
     * @param throttleGranted  a throttle budget unit was available
     * @return {@link ConvertCheck#CONVERTIBLE} when every guard passes, otherwise
     *         the first failing guard
     */
    public static ConvertCheck checkBlock(boolean ready, boolean enabled, boolean blacklisted,
                                          boolean alreadyManaged, boolean hasProduct,
                                          boolean chunkAllows, boolean throttleGranted) {
        if (!ready) {
            return ConvertCheck.NOT_READY;
        }
        if (!enabled) {
            return ConvertCheck.DISABLED;
        }
        if (blacklisted) {
            return ConvertCheck.BLACKLISTED;
        }
        if (alreadyManaged) {
            return ConvertCheck.ALREADY_MANAGED;
        }
        if (!hasProduct) {
            return ConvertCheck.NO_PRODUCT;
        }
        if (!chunkAllows) {
            return ConvertCheck.CHUNK_CAP;
        }
        if (!throttleGranted) {
            return ConvertCheck.THROTTLED;
        }
        return ConvertCheck.CONVERTIBLE;
    }
}
