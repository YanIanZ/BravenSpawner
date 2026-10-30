package dev.yanianz.bravenspawner.commerce.adapters;

import dev.yanianz.bravenspawner.commerce.AbstractPluginShopAdapter;
import me.gypopo.economyshopgui.api.EconomyShopGUIHook;
import me.gypopo.economyshopgui.util.EconomyType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Logger;

/** Typed EconomyShopGUI quotes for ordinary Vault-priced shop items. */
public final class EconomyShopGuiAdapter extends AbstractPluginShopAdapter {
    public EconomyShopGuiAdapter(Logger logger) {
        super(logger, "EconomyShopGUI", "EconomyShopGUI API 1.11.0", "EconomyShopGUI", "EconomyShopGUI-Premium");
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

    private static final class PriceAccess {
        private static boolean ready() {
            return EconomyShopGUIHook.class != null;
        }

        private static double price(Player player, ItemStack unit) {
            var item = player == null ? EconomyShopGUIHook.getShopItem(unit)
                    : EconomyShopGUIHook.getShopItem(player, unit);
            if (item == null || item.hasItemError() || !EconomyShopGUIHook.isSellAble(item)
                    || item.getEcoType() == null || item.getEcoType().getType() != EconomyType.VAULT
                    || item.getEcoType().getCurrency() != null || item.getLimitedSellMode() != 0
                    || EconomyShopGUIHook.hasMultipleSellPrices(item)) return -1.0;
            Double quote = player == null ? EconomyShopGUIHook.getItemSellPrice(item, unit)
                    : EconomyShopGUIHook.getItemSellPrice(item, unit, player);
            return quote == null ? -1.0 : quote;
        }
    }
}
