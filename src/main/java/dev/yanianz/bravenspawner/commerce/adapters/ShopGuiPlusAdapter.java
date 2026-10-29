package dev.yanianz.bravenspawner.commerce.adapters;

import dev.yanianz.bravenspawner.commerce.AbstractPluginShopAdapter;

import java.util.logging.Logger;

/**
 * ShopGUI+ price adapter.
 *
 * <p>ShopGUI+ exposes item values through the static {@code ShopGuiPlusApi}
 * class. A static API cannot be reached without either compiling against it or
 * using reflection, and this plugin does neither, so the adapter can detect the
 * plugin but must not pretend it can price an item.
 */
public final class ShopGuiPlusAdapter extends AbstractPluginShopAdapter {

    public ShopGuiPlusAdapter(Logger logger) {
        super(logger, "ShopGUI+", "ShopGuiPlusApi (a static shop API, not a Bukkit service)",
                "ShopGUIPlus", "ShopGUI+");
    }
}
