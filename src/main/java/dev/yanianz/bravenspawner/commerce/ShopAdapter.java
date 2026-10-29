package dev.yanianz.bravenspawner.commerce;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * One shop plugin's price source, isolated behind a shape the rest of the plugin
 * can use without knowing the shop's API.
 *
 * <p>Adapters exist because every shop answers the same question differently:
 * some expose a static API, some only expose their GUI, and several expose
 * nothing public at all. The adapter is the only class allowed to know which of
 * those is true, so the sale path never guesses a price.
 *
 * <p>An adapter that cannot read prices must report {@link #available()} as
 * false. {@link #priceOf(ItemStack)} returning {@code -1} is the "no opinion"
 * answer and is never silently interpreted as a free item; the bridge skips it
 * and the local {@code sell-prices} table decides instead. This build carries no
 * compile dependency on any shop plugin and forbids reflection, so an adapter
 * may only become available through a channel that is genuinely on the
 * classpath — never through a guessed class name.
 */
public interface ShopAdapter {

    /** Human-readable shop name, used in diagnostics and startup logs. */
    String name();

    /** True when the shop plugin is present in Bukkit's plugin manager. */
    boolean installed();

    /**
     * True only when the shop is installed <em>and</em> this build can read a
     * sell price from it without reflection.
     */
    boolean available();

    /**
     * Sell price of one unit of {@code stack}, in the shop's currency.
     *
     * @return a positive price, or {@code -1} when the adapter cannot price the
     *         item (unsupported item, unavailable shop, or not a sellable good)
     */
    double priceOf(ItemStack stack);

    /**
     * Sells the stacks through the shop and returns the total actually credited.
     *
     * <p>The default is zero because pricing and transacting are separate
     * capabilities: an adapter that can quote a price still has no way to move
     * items into the shop's inventory or trigger its payout. Zero means "nothing
     * was credited", which callers must treat as a refusal and leave storage
     * untouched. Implementing this in an adapter is only correct when the shop
     * exposes a real, non-reflective transaction entry point.
     */
    default double sell(Player player, List<ItemStack> stacks) {
        return 0.0;
    }

    /**
     * One-line explanation for an unavailable adapter, for the single startup
     * warning. Null when the adapter is available.
     */
    String unavailableReason();
}
