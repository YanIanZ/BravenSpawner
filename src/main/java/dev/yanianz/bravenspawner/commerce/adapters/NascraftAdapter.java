package dev.yanianz.bravenspawner.commerce.adapters;

import dev.yanianz.bravenspawner.commerce.AbstractPluginShopAdapter;
import dev.yanianz.bravenspawner.econ.Reflection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Nascraft market sell quotes, read by reflection.
 *
 * <p>Verified against Bounser/Nascraft: {@code MarketManager.getInstance()},
 * {@code MarketManager#getItem(ItemStack)}, {@code Item#getPrice()} and
 * {@code Price#getSellPrice()} (the reference bridge uses the same chain through
 * {@code NascraftAPI#getMarketManager()}). {@code getInstance()} constructs a
 * new market when none exists, so the private static {@code instance} field is
 * checked first and the adapter stays unavailable until Nascraft has built it.
 */
public final class NascraftAdapter extends AbstractPluginShopAdapter {

    private record Access(Plugin owner, Field instance, Method getItem, Method getPrice, Method sellPrice) {}

    private volatile Access access;

    public NascraftAdapter(Logger logger) {
        super(logger, "Nascraft", "Nascraft's MarketManager", "Nascraft");
    }

    private Access access() {
        Plugin owner = plugin();
        if (owner == null || !owner.isEnabled()) return null;
        Access cached = access;
        if (cached != null && cached.owner() == owner) return cached;
        try {
            Class<?> manager = Reflection.load(owner, "me.bounser.nascraft.market.MarketManager");
            Class<?> item = Reflection.load(owner, "me.bounser.nascraft.market.unit.Item");
            Class<?> price = Reflection.load(owner, "me.bounser.nascraft.market.unit.Price");
            Field instance = manager.getDeclaredField("instance");
            instance.setAccessible(true);
            Access built = new Access(owner, instance,
                    Reflection.method(manager, "getItem", ItemStack.class),
                    Reflection.method(item, "getPrice"),
                    Reflection.method(price, "getSellPrice"));
            access = built;
            return built;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            reportFailure(error);
            return null;
        }
    }

    private Object market(Access current) {
        try {
            return current.instance().get(null);
        } catch (IllegalAccessException denied) {
            return null;
        }
    }

    @Override
    protected boolean apiPresent() {
        Access current = access();
        return current != null && market(current) != null;
    }

    @Override
    public double priceOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !available()) return -1.0;
        Access current = access();
        if (current == null) return -1.0;
        try {
            Object market = market(current);
            if (market == null) return -1.0;
            Object item = Reflection.invoke(current.getItem(), market, stack);
            if (item == null) return -1.0;
            Object price = Reflection.invoke(current.getPrice(), item);
            if (price == null) return -1.0;
            double sell = Reflection.asDouble(Reflection.invoke(current.sellPrice(), price));
            return Double.isFinite(sell) && sell > 0.0 ? sell : -1.0;
        } catch (RuntimeException | LinkageError error) {
            reportFailure(error);
            return -1.0;
        }
    }
}
