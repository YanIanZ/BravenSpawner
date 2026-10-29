package dev.yanianz.bravenspawner.product;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable view of the product catalogue, swappable on reload.
 *
 * <p>The whole catalogue is published as one snapshot object behind a volatile
 * field. Readers — production cycles, the shop GUI, the command handler — never
 * lock and never observe a half-reloaded catalogue: they either see the previous
 * complete map or the next complete map. Building the maps in place and clearing
 * them for a reload would be faster to write and would let a tick pass read a
 * half-empty shop.
 *
 * <p>Reload happens at most once per command, so the copy cost is irrelevant; the
 * read path is the one that has to stay cheap.
 */
public final class ProductRegistry {

    private final ProductLoader loader;
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /**
     * Creates the registry and loads the catalogue immediately.
     *
     * <p>Loading in the constructor keeps boot simple: a caller that has a registry
     * always has products, so no subsystem can accidentally run against an empty
     * catalogue between construction and a separate {@code load()} call. The work
     * is file I/O, so construct only from the enable/reload path.
     */
    public ProductRegistry(ProductLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
        reload();
    }

    /** Re-scans both directories and atomically replaces the snapshot. */
    public void reload() {
        ProductLoader.LoadResult result = loader.load();
        this.snapshot = new Snapshot(result.mobs(), result.items(), result.malformed());
    }

    /** Every mob product, in file-name order. */
    public Collection<ProductDefinition> allMobs() {
        return snapshot.mobs().values();
    }

    /** Every item product, in file-name order. */
    public Collection<ProductDefinition> allItems() {
        return snapshot.items().values();
    }

    /**
     * Looks up one product by kind and id, case-insensitively.
     *
     * @return the definition, or {@code null} when the file is absent or malformed
     */
    public ProductDefinition get(ProductDefinition.Kind kind, String id) {
        if (kind == null || id == null) return null;
        Snapshot current = snapshot;
        Map<String, ProductDefinition> source = kind == ProductDefinition.Kind.MOB
                ? current.mobs()
                : current.items();
        return source.get(id.toLowerCase(Locale.ROOT));
    }

    public ProductDefinition mob(String id) {
        return get(ProductDefinition.Kind.MOB, id);
    }

    public ProductDefinition item(String id) {
        return get(ProductDefinition.Kind.ITEM, id);
    }

    public int mobCount() {
        return snapshot.mobs().size();
    }

    public int itemCount() {
        return snapshot.items().size();
    }

    /** Malformed files reported by the last scan; useful for {@code /spawner reload}. */
    public int malformed() {
        return snapshot.malformed();
    }

    /** One complete catalogue. */
    private record Snapshot(Map<String, ProductDefinition> mobs,
                            Map<String, ProductDefinition> items,
                            int malformed) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), 0);

        Snapshot {
            mobs = Collections.unmodifiableMap(new LinkedHashMap<>(mobs));
            items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
        }
    }
}
