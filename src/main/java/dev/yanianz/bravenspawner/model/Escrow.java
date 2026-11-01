package dev.yanianz.bravenspawner.model;

import java.util.List;
import java.util.UUID;

/**
 * Stock held out of storage while its sale is paid (STOR-01, VSS-05).
 *
 * <p>A sale first moves the priced quantities from storage into an escrow and saves
 * the record; only after that save is confirmed is the economy asked to pay. The
 * escrow therefore lives inside the spawner record and changes atomically with the
 * stock: a crash either leaves the units in storage (nothing saved yet) or in an
 * escrow whose payment outcome is unknown. A persisted escrow is never silently
 * re-sold or returned; an administrator resolves it with {@code /bspawner escrow}.
 *
 * @param id        sale id, also used in audit records
 * @param payee     who the payment was for
 * @param currency  sell currency id at quote time
 * @param value     quoted payout
 * @param createdAt epoch milliseconds
 * @param items     the held units, one entry per identity
 */
public record Escrow(UUID id, UUID payee, String currency, double value, long createdAt, List<StockEntry> items) {

    /** Most escrows one record may carry; keeps a single record's payload bounded. */
    public static final int MAX_PER_SPAWNER = 16;

    public Escrow {
        if (id == null || payee == null) throw new IllegalArgumentException("escrow id and payee are required");
        if (currency == null) currency = "";
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("escrow value " + value);
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("escrow holds no items");
        if (items.size() > StockLimits.HARD_MAX_DISTINCT) throw new IllegalArgumentException("escrow too large");
        items = List.copyOf(items);
    }

    /** Total held units. */
    public long quantity() {
        long total = 0;
        for (StockEntry entry : items) total += entry.quantity();
        return total;
    }
}
