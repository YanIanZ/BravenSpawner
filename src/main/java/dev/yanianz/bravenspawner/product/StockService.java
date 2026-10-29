package dev.yanianz.bravenspawner.product;

import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Binds {@link Stock} reserves to live spawners and clamps production by them.
 *
 * <p>Reserves are created lazily on the first production cycle of a spawner, not
 * eagerly for every loaded row at boot. A server with tens of thousands of
 * spawners would otherwise allocate a reserve for each one at startup, most of
 * which may never tick in a given session. Lazy creation is keyed by spawner id,
 * so two region threads producing for the same block share one reserve instead of
 * each keeping its own count.
 *
 * <p>The map is a {@link ConcurrentHashMap}: production runs on region threads and
 * pruning runs wherever the caller invokes it. {@link Stock}'s own monitor covers
 * the tick/spend pair; the map only has to make the handle visible.
 *
 * <p>Cached reserves are invalidated when the configured ceiling or refill changes
 * (a reload), so tuning a product takes effect without restarting. Removing a
 * spawner is handled two ways: callers can {@link #remove(Spawner)} directly, and
 * a periodic {@link #prune()} drops entries whose id the manager no longer knows,
 * which covers removals that happen through a path that does not know about this
 * service.
 */
public final class StockService {

    /**
     * Prune cadence in production calls.
     *
     * <p>Pruning on every call would turn a cheap clamp into a full map scan.
     * Pruning never would leak one small reserve per removed spawner until restart.
     * A power-of-two counter keeps the scan amortised to roughly one id per
     * thousand productions and costs one atomic increment.
     */
    private static final int PRUNE_EVERY = 512;

    private final SpawnerManager manager;
    private final ProductRegistry registry;
    private final ConcurrentHashMap<UUID, Stock> stocks = new ConcurrentHashMap<>();
    private final AtomicInteger productionCalls = new AtomicInteger();

    public StockService(SpawnerManager manager, ProductRegistry registry) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Advances one production cycle for a spawner and returns the granted amount.
     *
     * <p>This is the only entry point a production pass needs: it refills the
     * reserve for the cycle, spends what the caller requested against it, and
     * returns the amount that may actually be emitted. A product with stock
     * disabled returns the request unchanged, so the feature is opt-out without a
     * branch at every call site.
     *
     * <p>Exactly one call represents one cycle. Calling it twice in a cycle would
     * refill twice; callers that need to inspect a reserve without spending it use
     * {@link #available(Spawner)}.
     */
    public int produced(Spawner spawner, int requested) {
        if (spawner == null || requested <= 0) return 0;
        ProductDefinition definition = definition(spawner);
        if (definition == null || !definition.stockEnabled()) return requested;

        Stock stock = reserve(spawner.id(), definition);
        stock.tick();
        int granted = stock.produced(requested);

        if ((productionCalls.incrementAndGet() & (PRUNE_EVERY - 1)) == 0) prune();
        return granted;
    }

    /**
     * The live reserve for a spawner, or {@code null} when the product keeps no
     * stock (feature disabled or unknown product). Null is the documented "no
     * limit" signal rather than a sentinel reserve, so callers cannot accidentally
     * treat an unlimited product as exhausted.
     */
    public Stock stock(Spawner spawner) {
        if (spawner == null) return null;
        ProductDefinition definition = definition(spawner);
        if (definition == null || !definition.stockEnabled()) return null;
        return reserve(spawner.id(), definition);
    }

    /** Units currently available, or {@link Integer#MAX_VALUE} when unlimited. */
    public int available(Spawner spawner) {
        Stock stock = stock(spawner);
        return stock == null ? Integer.MAX_VALUE : stock.remaining();
    }

    /** Drops one spawner's reserve; used by the removal path. */
    public void remove(Spawner spawner) {
        if (spawner != null) stocks.remove(spawner.id());
    }

    /** Drops one reserve by id, for callers that only keep the id. */
    public void forget(UUID spawnerId) {
        if (spawnerId != null) stocks.remove(spawnerId);
    }

    /**
     * Drops every reserve whose spawner is no longer registered with the manager.
     *
     * <p>Safe to call from any thread: the map is concurrent and a reserve for a
     * spawner registered between the lookup and the removal is simply recreated on
     * its next cycle. That race is harmless; keeping a dead id forever is not.
     */
    public void prune() {
        for (UUID id : stocks.keySet()) {
            if (manager.byId(id) == null) stocks.remove(id);
        }
    }

    /** Number of live reserves; diagnostics and tests only. */
    public int tracked() {
        return stocks.size();
    }

    /**
     * Returns the cached reserve, or a fresh one when the product's limits changed.
     *
     * <p>Resetting on change is intentional: keeping the old remaining count under
     * a new ceiling would either exceed the new maximum or hide the newly granted
     * head-room, depending on which direction the config moved.
     */
    private Stock reserve(UUID id, ProductDefinition definition) {
        return stocks.compute(id, (key, current) -> {
            if (current != null
                    && current.maxStock() == definition.maxStock()
                    && current.restockPerCycle() == definition.restockPerCycle()) {
                return current;
            }
            return new Stock(definition.maxStock(), definition.restockPerCycle());
        });
    }

    private ProductDefinition definition(Spawner spawner) {
        Spawner.Kind kind = spawner.kind();
        if (kind == null) return null;
        ProductDefinition.Kind productKind = kind == Spawner.Kind.MOB
                ? ProductDefinition.Kind.MOB
                : ProductDefinition.Kind.ITEM;
        return registry.get(productKind, spawner.product());
    }
}
