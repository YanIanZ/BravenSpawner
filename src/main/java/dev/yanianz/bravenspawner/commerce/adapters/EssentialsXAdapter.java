package dev.yanianz.bravenspawner.commerce.adapters;

import com.earth2me.essentials.IEssentials;
import dev.yanianz.bravenspawner.commerce.AbstractPluginShopAdapter;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.logging.Logger;

/** Typed EssentialsX worth lookup. Quotes are per item; settlement remains Vault. */
public final class EssentialsXAdapter extends AbstractPluginShopAdapter {
    public EssentialsXAdapter(Logger logger) {
        super(logger, "EssentialsX", "EssentialsX Worth API", "Essentials", "EssentialsX");
    }

    @Override
    protected boolean apiPresent() {
        return WorthAccess.ready(plugin());
    }

    @Override
    public double priceOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !available()) return -1.0;
        try {
            ItemStack unit = stack.clone();
            unit.setAmount(1);
            double price = WorthAccess.price(plugin(), unit);
            return Double.isFinite(price) && price > 0.0 ? price : -1.0;
        } catch (RuntimeException | LinkageError error) {
            reportFailure(error);
            return -1.0;
        }
    }

    // Resolve optional types only after the named plugin has been found.
    private static final class WorthAccess {
        private static boolean ready(Plugin plugin) {
            return plugin instanceof IEssentials essentials && essentials.getWorth() != null;
        }

        private static double price(Plugin plugin, ItemStack unit) {
            IEssentials essentials = (IEssentials) plugin;
            BigDecimal price = essentials.getWorth().getPrice(essentials, unit);
            return price == null ? -1.0 : price.doubleValue();
        }
    }
}
