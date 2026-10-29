package dev.yanianz.bravenspawner.commerce.adapters;

import dev.yanianz.bravenspawner.commerce.AbstractPluginShopAdapter;

import java.util.logging.Logger;

/**
 * ExcellentShop price adapter.
 *
 * <p>ExcellentShop keeps its price registry inside the plugin instance and does
 * not publish it as a Bukkit service. The registry can only be reached through
 * the shop's own API, which is not a compile dependency here, so the adapter
 * refuses to price rather than reading config files that may be stale relative
 * to the running shop.
 */
public final class ExcellentShopAdapter extends AbstractPluginShopAdapter {

    public ExcellentShopAdapter(Logger logger) {
        super(logger, "ExcellentShop", "ExcellentShop's price API (internal, not a Bukkit service)",
                "ExcellentShop");
    }
}
