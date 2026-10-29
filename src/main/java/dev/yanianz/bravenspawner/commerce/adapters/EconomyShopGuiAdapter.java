package dev.yanianz.bravenspawner.commerce.adapters;

import dev.yanianz.bravenspawner.commerce.AbstractPluginShopAdapter;

import java.util.logging.Logger;

/**
 * EconomyShopGUI price adapter.
 *
 * <p>The free and premium editions ship the same price model behind their own
 * API classes; neither registers a Bukkit service this plugin could consume
 * generically. Since the API is not a compile dependency and reflection is
 * forbidden, the adapter reports unavailable instead of guessing a value from
 * the shop's config files, which would silently diverge from in-game prices.
 */
public final class EconomyShopGuiAdapter extends AbstractPluginShopAdapter {

    public EconomyShopGuiAdapter(Logger logger) {
        super(logger, "EconomyShopGUI", "EconomyShopGUIAPI (a shop API, not a Bukkit service)",
                "EconomyShopGUI", "EconomyShopGUI-Premium");
    }
}
