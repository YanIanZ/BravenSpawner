package dev.yanianz.bravenspawner.commerce.adapters;

import dev.yanianz.bravenspawner.commerce.AbstractPluginShopAdapter;
import dev.yanianz.bravenspawner.econ.Reflection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * DonutWorth item worth, read by reflection.
 *
 * <p>DonutWorth is a paid, closed-source plugin with no published API docs.
 * The only evidence for its API is the reference plugin's bridge bytecode,
 * which calls the static {@code me.serbob.donutworth.api.util.Prices#getPrice(ItemStack)}
 * returning a {@code double}. If that method is missing the adapter stays
 * unavailable and local {@code sell-prices} apply.
 */
public final class DonutWorthAdapter extends AbstractPluginShopAdapter {

    private record Access(Plugin owner, Method price) {}

    private volatile Access access;

    public DonutWorthAdapter(Logger logger) {
        super(logger, "DonutWorth", "DonutWorth's Prices.getPrice(ItemStack)", "DonutWorth");
    }

    private Access access() {
        Plugin owner = plugin();
        if (owner == null || !owner.isEnabled()) return null;
        Access cached = access;
        if (cached != null && cached.owner() == owner) return cached;
        try {
            Class<?> prices = Reflection.load(owner, "me.serbob.donutworth.api.util.Prices");
            Access built = new Access(owner, Reflection.method(prices, "getPrice", ItemStack.class));
            access = built;
            return built;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            reportFailure(error);
            return null;
        }
    }

    @Override
    protected boolean apiPresent() {
        return access() != null;
    }

    @Override
    public double priceOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !available()) return -1.0;
        Access current = access();
        if (current == null) return -1.0;
        try {
            double price = Reflection.asDouble(Reflection.invoke(current.price(), null, stack));
            return Double.isFinite(price) && price > 0.0 ? price : -1.0;
        } catch (RuntimeException | LinkageError error) {
            reportFailure(error);
            return -1.0;
        }
    }
}
