package dev.yanianz.bravenspawner.commerce.adapters;

import dev.yanianz.bravenspawner.commerce.AbstractPluginShopAdapter;
import net.brcdev.shopgui.ShopGuiPlusApi;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Logger;

/** Typed ShopGUI+ sell quotes, queried only after its shops finish loading. */
public final class ShopGuiPlusAdapter extends AbstractPluginShopAdapter {
    public ShopGuiPlusAdapter(Logger logger) {
        super(logger, "ShopGUI+", "ShopGUI+ 3.2 price API and loaded shops", "ShopGUIPlus", "ShopGUI+");
    }

    @Override
    protected boolean apiPresent() {
        return PriceAccess.ready();
    }

    @Override
    public double priceOf(ItemStack stack) {
        return priceOf(null, stack);
    }

    @Override
    public double priceOf(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !available()) return -1.0;
        try {
            ItemStack unit = stack.clone();
            unit.setAmount(1);
            double price = PriceAccess.price(player, unit);
            return Double.isFinite(price) && price > 0.0 ? price : -1.0;
        } catch (RuntimeException | LinkageError error) {
            reportFailure(error);
            return -1.0;
        }
    }

    // No provider registration or shop access from our constructor/onEnable.
    private static final class PriceAccess {
        private static boolean ready() {
            var shop = ShopGuiPlusApi.getPlugin();
            return shop != null && shop.getShopManager() != null && shop.getShopManager().areShopsLoaded();
        }

        private static double price(Player player, ItemStack unit) {
            return player == null ? ShopGuiPlusApi.getItemStackPriceSell(unit)
                    : ShopGuiPlusApi.getItemStackPriceSell(player, unit);
        }
    }
}
