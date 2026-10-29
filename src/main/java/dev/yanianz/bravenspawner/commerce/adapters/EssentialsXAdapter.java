package dev.yanianz.bravenspawner.commerce.adapters;

import dev.yanianz.bravenspawner.commerce.AbstractPluginShopAdapter;

import java.util.logging.Logger;

/**
 * EssentialsX price adapter.
 *
 * <p>EssentialsX publishes {@code IEssentials} as a Bukkit service for player
 * data, but item worth lives in its separate Worth module and is not part of
 * that service. The worth registry is not a compile dependency here, so the
 * adapter can notice EssentialsX yet cannot quote its prices; reporting
 * unavailable keeps the local table authoritative until a real worth service is
 * wired in.
 */
public final class EssentialsXAdapter extends AbstractPluginShopAdapter {

    public EssentialsXAdapter(Logger logger) {
        super(logger, "EssentialsX", "the Worth module's registry (not exposed through the IEssentials service)",
                "Essentials", "EssentialsX");
    }
}
