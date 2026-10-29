package dev.yanianz.bravenspawner.commerce;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Base for adapters that identify a shop by its Bukkit plugin name.
 *
 * <p>Discovery deliberately stops at {@link Bukkit#getPluginManager()}: a name
 * lookup is the only probe that is both cheap and safe on every host, and it
 * cannot accidentally trigger a shop's API initialisation or load a class that
 * is not installed. The price capability itself is supplied by
 * {@link #apiPresent()}, which is false in this build because no shop API is on
 * the compile classpath and reflection is forbidden. Reporting unavailable is
 * the honest answer; inventing a price would let the plugin delete loot for
 * money that was never quoted.
 *
 * <p>The unavailable warning is emitted once per adapter instance. A reload may
 * legitimately build a new bridge, and then a second warning is correct because
 * the resolver's behaviour really did change.
 */
public abstract class AbstractPluginShopAdapter implements ShopAdapter {

    private final Logger logger;
    private final String displayName;
    private final String requiredApi;
    private final List<String> pluginNames;
    private final AtomicBoolean warned = new AtomicBoolean();

    private volatile Plugin plugin;
    private volatile boolean probed;

    protected AbstractPluginShopAdapter(Logger logger, String displayName, String requiredApi, String... pluginNames) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.requiredApi = Objects.requireNonNull(requiredApi, "requiredApi");
        this.pluginNames = List.of(pluginNames);
        if (this.pluginNames.isEmpty()) throw new IllegalArgumentException("pluginNames");
    }

    @Override
    public final String name() {
        return displayName;
    }

    @Override
    public final boolean installed() {
        return plugin() != null;
    }

    /**
     * Resolves availability once per call and logs the reason the first time an
     * installed shop is refused.
     *
     * <p>Availability is intentionally not cached as a boolean: an adapter that
     * overrides {@link #apiPresent()} may observe a late-registered service, and
     * a stale "false" would keep a working price source disabled for the whole
     * session.
     */
    @Override
    public final boolean available() {
        Plugin present = plugin();
        if (present == null) return false;
        if (apiPresent()) return true;
        if (warned.compareAndSet(false, true)) {
            logger.warning(unavailableReason());
        }
        return false;
    }

    /**
     * Whether this build can call the shop's price API.
     *
     * <p>False here by design. A subclass may only override this when its shop
     * client is a real compile dependency of this plugin; a reflective bridge is
     * not an acceptable implementation and would break on any shop update.
     */
    protected boolean apiPresent() {
        return false;
    }

    /**
     * Returns {@code -1} because this adapter has no price source. Kept concrete
     * so the bridge can ask first and fall back to the local table without a
     * special case.
     */
    @Override
    public double priceOf(ItemStack stack) {
        return -1.0;
    }

    @Override
    public final String unavailableReason() {
        Plugin present = plugin();
        if (present == null) {
            return displayName + " is not installed (looked for " + String.join(", ", pluginNames) + ").";
        }
        return displayName + " is installed as '" + present.getName()
                + "', but its prices live behind " + requiredApi
                + "; this build has no compile dependency on that API and reflection is forbidden,"
                + " so the shop reports unavailable and the local sell-prices table is used instead.";
    }

    /**
     * The first installed plugin name, resolved once per adapter.
     *
     * <p>Plugins cannot appear after {@code onEnable} on Paper or Folia, and the
     * bridge is constructed there, so caching the absence keeps a price lookup
     * from walking the plugin manager for every item in a large sell quote.
     */
    private Plugin plugin() {
        Plugin cached = plugin;
        if (cached != null) return cached;
        if (probed) return null;
        for (String candidate : pluginNames) {
            Plugin found;
            try {
                found = Bukkit.getPluginManager().getPlugin(candidate);
            } catch (RuntimeException e) {
                // An incomplete host can throw while enumerating plugins; treating
                // that as "not installed" keeps boot deterministic.
                probed = true;
                return null;
            }
            if (found != null) {
                plugin = found;
                return found;
            }
        }
        probed = true;
        return null;
    }
}
