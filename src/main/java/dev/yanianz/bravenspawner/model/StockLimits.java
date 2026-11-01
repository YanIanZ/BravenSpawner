package dev.yanianz.bravenspawner.model;

/**
 * Capacity limits for one spawner's virtual storage.
 *
 * <p>Three independent limits, each {@code 0} for "no gameplay limit":
 * {@code maxTotal} caps the sum of all quantities, {@code maxDistinct} the number of
 * different item identities and {@code maxPerItem} the quantity of any one identity.
 * Hard technical bounds always apply on top: quantities are {@code long} with checked
 * arithmetic and the distinct count never exceeds {@link #HARD_MAX_DISTINCT}, the
 * largest entry count the storage codec can persist.
 */
public record StockLimits(long maxTotal, int maxDistinct, long maxPerItem) {

    /** New identities stop here; the codec keeps headroom above it for returned units. */
    public static final int HARD_MAX_DISTINCT = 4096;

    public static final StockLimits UNLIMITED = new StockLimits(0, 0, 0);

    public StockLimits {
        maxTotal = Math.max(0, maxTotal);
        maxDistinct = Math.max(0, maxDistinct);
        maxPerItem = Math.max(0, maxPerItem);
    }

    /** Only a total cap, as configured by {@code max-stored-items}. */
    public static StockLimits total(long maxTotal) {
        return new StockLimits(maxTotal, 0, 0);
    }

    long effectiveTotal() { return maxTotal <= 0 ? Long.MAX_VALUE : maxTotal; }

    int effectiveDistinct() {
        return maxDistinct <= 0 ? HARD_MAX_DISTINCT : Math.min(maxDistinct, HARD_MAX_DISTINCT);
    }

    long effectivePerItem() { return maxPerItem <= 0 ? Long.MAX_VALUE : maxPerItem; }
}
