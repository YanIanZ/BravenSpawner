package dev.yanianz.bravenspawner.commerce;

import dev.yanianz.bravenspawner.config.Settings;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Resolves the unit sell price of an item: shop first, local table second.
 *
 * <p>A live shop is authoritative because it is the thing that will actually pay:
 * its price may depend on the shop's own market state, region, or per-item
 * metadata that this plugin cannot see. Only when no shop can answer does the
 * resolver fall back to {@code sell-prices}, which is the admin's static table.
 *
 * <p>The fallback applies {@code economy.sell-multiplier} exactly as the sale
 * path does, so a quote and a payout cannot disagree. Shop quotes are returned
 * unchanged because the shop already applied its own pricing rules.
 *
 * <p>Both inputs are suppliers, not snapshots: {@code reloadConfig()} replaces
 * the {@link Settings} object, and a cached map would keep paying last boot's
 * prices until restart.
 */
public final class PriceResolver {

    private final ShopBridge shops;
    private final Supplier<Map<String, Double>> sellPrices;
    private final DoubleSupplier multiplier;

    /**
     * Resolver with no local multiplier, for hosts that price from the table
     * as-is.
     */
    public PriceResolver(ShopBridge shops, Supplier<Map<String, Double>> sellPrices) {
        this(shops, sellPrices, () -> 1.0);
    }

    /**
     * Resolver over explicit price sources, so a reload or a test can swap the
     * table without rebuilding the shop bridge.
     *
     * @param shops      the shop router, never null
     * @param sellPrices live supplier of uppercase material name to unit price
     * @param multiplier live supplier of the configured sell multiplier
     */
    public PriceResolver(ShopBridge shops,
                         Supplier<Map<String, Double>> sellPrices,
                         DoubleSupplier multiplier) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.sellPrices = Objects.requireNonNull(sellPrices, "sellPrices");
        this.multiplier = Objects.requireNonNull(multiplier, "multiplier");
    }

    /**
     * Resolver bound to a live {@link Settings} supplier, for the normal boot path.
     *
     * <p>Reads the supplier on every call so the resolver follows reloads; before
     * the first load the supplier may return null and the resolver reports every
     * item as unsellable rather than throwing on a region thread.
     */
    public static PriceResolver fromSettings(ShopBridge shops, Supplier<Settings> settings) {
        Objects.requireNonNull(settings, "settings");
        return new PriceResolver(
                shops,
                () -> {
                    Settings current = settings.get();
                    return current == null ? Map.of() : current.sellPrices;
                },
                () -> {
                    Settings current = settings.get();
                    return current == null || current.economy == null
                            ? 1.0
                            : current.economy.sellMultiplier;
                });
    }

    /**
     * Unit sell price of {@code stack}.
     *
     * <p>Empty means unsellable: air, an item no shop prices and no table entry,
     * or a non-positive/non-finite result. Callers must not default the empty
     * case to zero and delete the item anyway; an unsellable item is kept.
     */
    public OptionalDouble resolve(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return OptionalDouble.empty();
        }

        double shopPrice = shops.priceOf(stack);
        if (Double.isFinite(shopPrice) && shopPrice > 0.0) {
            return OptionalDouble.of(shopPrice);
        }

        Map<String, Double> table = sellPrices.get();
        if (table == null) return OptionalDouble.empty();
        Double unit = table.get(stack.getType().name());
        if (unit == null || !Double.isFinite(unit) || unit <= 0.0) {
            return OptionalDouble.empty();
        }

        double scale = multiplier.getAsDouble();
        // SellService refuses a sale when the multiplier is zero or negative.
        // A non-finite value fails closed for the same reason: quoting a price
        // the payout cannot honour is worse than reporting the item unsellable.
        if (!Double.isFinite(scale) || scale <= 0.0) return OptionalDouble.empty();
        double price = unit * scale;
        return Double.isFinite(price) && price > 0.0
                ? OptionalDouble.of(price)
                : OptionalDouble.empty();
    }

    /** The shop currently supplying prices, empty when the local table is authoritative. */
    public Optional<ShopAdapter> shop() {
        return shops.active();
    }
}
