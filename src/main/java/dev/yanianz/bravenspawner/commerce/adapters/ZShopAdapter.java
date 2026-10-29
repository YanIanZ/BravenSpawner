package dev.yanianz.bravenspawner.commerce.adapters;

import dev.yanianz.bravenspawner.commerce.AbstractPluginShopAdapter;

import java.util.logging.Logger;

/**
 * ZShop price adapter.
 *
 * <p>ZShop has no public Bukkit service and its price tables are internal to the
 * plugin. Without its API on the classpath there is no non-reflective way to ask
 * for a sell price, so detection is reported but pricing is refused.
 */
public final class ZShopAdapter extends AbstractPluginShopAdapter {

    public ZShopAdapter(Logger logger) {
        super(logger, "ZShop", "ZShop's price API (internal, not a Bukkit service)",
                "zShop", "ZShop");
    }
}
