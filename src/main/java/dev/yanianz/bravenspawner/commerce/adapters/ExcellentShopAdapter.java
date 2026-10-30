package dev.yanianz.bravenspawner.commerce.adapters;

import dev.yanianz.bravenspawner.commerce.AbstractPluginShopAdapter;
import dev.yanianz.bravenspawner.econ.Reflection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * ExcellentShop virtual-shop sell quotes, read by reflection.
 *
 * <p>Two package generations are tried, newest first:
 * <ul>
 *   <li>{@code su.nightexpress.excellentshop} (verified against
 *   nulli0n/ExcellentShop-spigot): static {@code ShopAPI.getVirtualShop()},
 *   {@code VirtualShopModule#getBestProductFor(ItemStack, TradeType)},
 *   {@code Product#getFinalPrice(TradeType, Player)} / {@code getPrice(TradeType)}
 *   and {@code getUnitSize()}.</li>
 *   <li>{@code su.nightexpress.nexshop} (older releases; shape taken from the
 *   reference plugin's bridge): the same calls, plus an optional
 *   {@code getBestProductFor(ItemStack, TradeType, Player)}.</li>
 * </ul>
 * A product's price is per product unit, so it is divided by the unit size to
 * give a per-item quote. A product priced in a non-default ExcellentShop
 * currency is still quoted as a number; settlement uses {@code economy.currencies.sell}.
 */
public final class ExcellentShopAdapter extends AbstractPluginShopAdapter {

    private record Generation(String api, String tradeType, String product) {}

    private static final Generation[] GENERATIONS = {
            new Generation("su.nightexpress.excellentshop.ShopAPI",
                    "su.nightexpress.excellentshop.api.product.TradeType",
                    "su.nightexpress.excellentshop.api.product.Product"),
            new Generation("su.nightexpress.nexshop.ShopAPI",
                    "su.nightexpress.nexshop.api.shop.type.TradeType",
                    "su.nightexpress.nexshop.api.shop.product.Product"),
    };

    private record Access(Plugin owner, Method virtualShop, Object sell, Class<?> tradeType,
                          Method finalPrice, Method price, Method unitSize) {}

    private volatile Access access;

    public ExcellentShopAdapter(Logger logger) {
        super(logger, "ExcellentShop", "ExcellentShop's ShopAPI virtual shop", "ExcellentShop");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Access access() {
        Plugin owner = plugin();
        if (owner == null || !owner.isEnabled()) return null;
        Access cached = access;
        if (cached != null && cached.owner() == owner) return cached;
        for (Generation generation : GENERATIONS) {
            try {
                Class<?> api = Reflection.load(owner, generation.api());
                Class<?> trade = Reflection.load(owner, generation.tradeType());
                Class<?> product = Reflection.load(owner, generation.product());
                Object sell = Enum.valueOf((Class) trade, "SELL");
                Access built = new Access(owner, Reflection.method(api, "getVirtualShop"), sell, trade,
                        Reflection.optional(product, "getFinalPrice", trade, Player.class),
                        Reflection.method(product, "getPrice", trade),
                        Reflection.optional(product, "getUnitSize"));
                access = built;
                return built;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError next) {
                // Try the older package layout.
            }
        }
        return null;
    }

    private Object module(Access current) {
        return Reflection.invoke(current.virtualShop(), null);
    }

    @Override
    protected boolean apiPresent() {
        Access current = access();
        return current != null && module(current) != null;
    }

    @Override
    public double priceOf(ItemStack stack) {
        return priceOf(null, stack);
    }

    @Override
    public double priceOf(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !available()) return -1.0;
        Access current = access();
        if (current == null) return -1.0;
        try {
            Object module = module(current);
            if (module == null) return -1.0;
            Object product = null;
            if (player != null) {
                Method withPlayer = Reflection.optional(module.getClass(), "getBestProductFor",
                        ItemStack.class, current.tradeType(), Player.class);
                if (withPlayer != null) product = Reflection.invoke(withPlayer, module, stack, current.sell(), player);
            }
            if (product == null) {
                Method best = Reflection.method(module.getClass(), "getBestProductFor", ItemStack.class, current.tradeType());
                product = Reflection.invoke(best, module, stack, current.sell());
            }
            if (product == null) return -1.0;
            double price = current.finalPrice() != null
                    ? Reflection.asDouble(Reflection.invoke(current.finalPrice(), product, current.sell(), player))
                    : Reflection.asDouble(Reflection.invoke(current.price(), product, current.sell()));
            if (current.unitSize() != null) {
                double size = Reflection.asDouble(Reflection.invoke(current.unitSize(), product));
                if (Double.isFinite(size) && size > 1) price /= size;
            }
            return Double.isFinite(price) && price > 0.0 ? price : -1.0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            reportFailure(error);
            return -1.0;
        }
    }
}
