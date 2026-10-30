package dev.yanianz.bravenspawner.commerce;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.sell.ShopHook;
import dev.yanianz.bravenspawner.commerce.adapters.EconomyShopGuiAdapter;
import dev.yanianz.bravenspawner.commerce.adapters.EssentialsXAdapter;
import dev.yanianz.bravenspawner.commerce.adapters.ExcellentShopAdapter;
import dev.yanianz.bravenspawner.commerce.adapters.ShopGuiPlusAdapter;
import dev.yanianz.bravenspawner.commerce.adapters.ZShopAdapter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Routes price questions to the first shop adapter that can answer them.
 *
 * <p>The production constructor selects only the shop named by {@code hooks.yml}.
 * Explicit adapter-list constructors preserve the caller's routing order. A single
 * price is never averaged or summed across shops: the first available adapter
 * with an opinion on the item wins, and a sale is offered only to the first
 * adapter that can actually transact, so the same stacks cannot be sold twice.
 *
 * <p>Construction performs the only probes this plugin makes: Bukkit's plugin
 * manager name lookup per adapter, plus each adapter's capability check. Nothing
 * here touches the event loop, opens a GUI, or schedules work.
 *
 * <p>Only adapters that report {@link ShopAdapter#available()} participate. A
 * shop that is installed but cannot be queried is logged once and skipped, which
 * is why a server can run this plugin with ShopGUI+ installed and still price
 * from {@code sell-prices} without a startup failure.
 */
public final class ShopBridge {

    private final Logger logger;
    private final List<ShopAdapter> adapters;

    /**
     * Builds the production adapter set and logs the routing decision once.
     *
     * <p>Call during {@code onEnable}, after plugins have loaded: the plugin
     * manager must already know which shops exist, and a bridge built earlier
     * would cache "not installed" for the session.
     */
    public ShopBridge(Logger logger) {
        this(logger, defaultAdapters(logger));
    }

    /** Production routing honors the single shop selected in hooks.yml. */
    public ShopBridge(BravenSpawner plugin) {
        this(plugin.getLogger(), configuredAdapters(plugin));
    }

    private static List<ShopAdapter> configuredAdapters(BravenSpawner plugin) {
        ShopHook hook = new ShopHook(plugin);
        hook.load();
        return select(defaultAdapters(plugin.getLogger()), hook.configuredShop());
    }

    /** Explicit configured routing, also usable by hosts with custom adapters. */
    public ShopBridge(Logger logger, List<ShopAdapter> adapters, String configuredShop) {
        this(logger, select(adapters, configuredShop));
    }

    private static List<ShopAdapter> select(List<ShopAdapter> adapters, String configuredShop) {
        String selected = normalize(configuredShop);
        if (selected.isEmpty() || selected.equals("NONE") || selected.equals("DISABLED")) return List.of();
        return adapters.stream().filter(adapter -> normalize(adapter.name()).equals(selected)).toList();
    }

    private static String normalize(String name) {
        if (name == null) return "";
        String key = name.trim().toUpperCase(java.util.Locale.ROOT).replace(" ", "");
        return switch (key) {
            case "SHOPGUIPLUS", "SHOPGUI+" -> "SHOPGUI+";
            case "ESSENTIALS", "ESSENTIALSX" -> "ESSENTIALSX";
            case "ECONOMYSHOPGUI-PREMIUM", "ECONOMYSHOPGUIPREMIUM" -> "ECONOMYSHOPGUI";
            default -> key;
        };
    }


    /**
     * Builds a bridge over an explicit adapter list, used by tests and by hosts
     * that add their own adapter without touching the built-in order.
     */
    public ShopBridge(Logger logger, List<ShopAdapter> adapters) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.adapters = List.copyOf(Objects.requireNonNull(adapters, "adapters"));
        announce();
    }

    /** The built-in adapter order, highest priority first. */
    public static List<ShopAdapter> defaultAdapters(Logger logger) {
        Objects.requireNonNull(logger, "logger");
        return List.of(
                new ShopGuiPlusAdapter(logger),
                new EconomyShopGuiAdapter(logger),
                new ZShopAdapter(logger),
                new ExcellentShopAdapter(logger),
                new EssentialsXAdapter(logger));
    }

    /** Immutable adapter list in routing order, for diagnostics and reload checks. */
    public List<ShopAdapter> adapters() {
        return adapters;
    }

    /** The first adapter that can price items, if any shop is usable today. */
    public Optional<ShopAdapter> active() {
        for (ShopAdapter adapter : adapters) {
            if (adapter.available()) return Optional.of(adapter);
        }
        return Optional.empty();
    }

    /**
     * Price of one unit of {@code stack} from the first adapter that can price
     * it, or {@code -1} when every adapter is unavailable or has no opinion.
     *
     * <p>{@code -1} is a sentinel, not a price: callers must treat it as "ask the
     * next source" and never as zero. Returning zero here would make an
     * unpriceable item look free and could delete loot for no money.
     */
    public double priceOf(ItemStack stack) {
        return priceOf(null, stack);
    }

    /** Unit quote retaining metadata and, when supplied, player price modifiers. */
    public double priceOf(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return -1.0;
        for (ShopAdapter adapter : adapters) {
            if (!adapter.available()) continue;
            // Virtual counts can exceed vanilla stack limits. Never pass those
            // counts to providers or modify the caller's stored item.
            ItemStack unit = stack.clone();
            unit.setAmount(1);
            double price = adapter.priceOf(player, unit);
            if (Double.isFinite(price) && price > 0.0) return price;
        }
        return -1.0;
    }

    /**
     * Sells the stacks through the highest-priority adapter that can transact,
     * returning the total the shop actually credited.
     *
     * <p>Air and null entries are dropped before the adapter sees the list so no
     * shop has to defend against a malformed caller. The first adapter that
     * reports a positive credit wins; later adapters are not asked, which is what
     * prevents the same stacks from being sold twice. Zero means nothing was
     * credited and storage must be left untouched.
     */
    public double sell(Player player, List<ItemStack> stacks) {
        if (player == null || stacks == null || stacks.isEmpty()) return 0.0;

        List<ItemStack> sellable = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                sellable.add(stack);
            }
        }
        if (sellable.isEmpty()) return 0.0;

        for (ShopAdapter adapter : adapters) {
            if (!adapter.available()) continue;
            double credited = adapter.sell(player, sellable);
            if (Double.isFinite(credited) && credited > 0.0) return credited;
        }
        return 0.0;
    }

    /**
     * Logs one line per installed shop and one summary line when none is present.
     *
     * <p>{@link ShopAdapter#available()} already emits the detailed refusal for an
     * installed-but-unqueryable shop, so this method intentionally does not
     * repeat it. Silence for an absent plugin is the normal case and must not
     * become five warning lines on every boot.
     */
    private void announce() {
        boolean anyInstalled = false;
        for (ShopAdapter adapter : adapters) {
            if (!adapter.installed()) continue;
            anyInstalled = true;
            if (adapter.available()) {
                logger.info("Shop bridge: " + adapter.name()
                        + " is available and supplies sell prices before sell-prices.");
            }
        }
        if (!anyInstalled) {
            logger.info("Shop bridge: no supported shop plugin is installed; sell prices come from sell-prices.");
        }
    }
}
