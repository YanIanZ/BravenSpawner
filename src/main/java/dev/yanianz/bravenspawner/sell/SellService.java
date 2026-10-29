package dev.yanianz.bravenspawner.sell;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Text;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Converts stored spawner loot into money, on demand and on a timer.
 *
 * <p>Every sale is priced before it is paid and paid before anything is removed:
 * an item with no {@code sell-prices} entry is never sellable, a storage snapshot
 * keeps the quoted value and the removed amount identical, and a missing economy
 * leaves the storage exactly as it was. Items produced between the quote and the
 * removal are preserved because only the priced stacks are subtracted.
 *
 * <p>The service is a per-plugin singleton installed from the GUI constructor,
 * which is the only subsystem the bootstrap creates during onEnable; that keeps
 * autosell scheduled at boot without wiring changes elsewhere. The repeating pass
 * uses the global region scheduler so it runs on Folia as well as Paper.
 */
public final class SellService {

    /** Outcome of a sale attempt, so the caller can word the feedback without re-checking config. */
    public enum Status { SOLD, NOTHING_TO_SELL, DISABLED, NO_ECONOMY, FAILED }

    /** A completed or refused sale: value is what was (or would have been) paid. */
    public record Sale(Status status, double value, int items) {
        static Sale refused(Status status) {
            return new Sale(status, 0.0, 0);
        }
    }

    private record Priced(List<ItemStack> sold, int items, double value) {}

    private record PriceTable(Map<String, Double> unitPrices, double multiplier) {}

    private static final long MIN_AUTOSELL_TICKS = 100L;

    private static volatile SellService instance;

    private final BravenSpawner plugin;
    private final ShopHook shop;
    private final Object saleLock = new Object();
    private final AtomicBoolean economyWarned = new AtomicBoolean();

    private volatile ScheduledTask autosellTask;

    private SellService(BravenSpawner plugin) {
        this.plugin = plugin;
        this.shop = new ShopHook(plugin);
        this.shop.load();
        startAutosell();
    }

    /**
     * Returns the singleton for this plugin instance, creating it on first call.
     *
     * <p>Creation is the install point: it loads the shop hooks and schedules the
     * autosell pass, so this must be called during onEnable — the GUI constructor
     * does exactly that, before any spawner can be ticked.
     */
    public static SellService install(BravenSpawner plugin) {
        if (plugin == null) throw new IllegalArgumentException("plugin");
        SellService local = instance;
        if (local != null && local.plugin == plugin) return local;
        synchronized (SellService.class) {
            if (instance == null || instance.plugin != plugin) {
                instance = new SellService(plugin);
            }
            return instance;
        }
    }

    /** The shop/currency detection built at install time. */
    public ShopHook shop() {
        return shop;
    }

    /** False when {@code disable-sell-feature: true}; the GUI and autosell both honour it. */
    public boolean enabled() {
        return !plugin.getConfig().getBoolean("disable-sell-feature", false);
    }

    /** Current sale value of what is stored, including the multiplier; used for the GUI quote. */
    public double value(Spawner spawner) {
        if (spawner == null) return 0.0;
        return price(spawner.contents(), prices()).value();
    }

    /** Number of stored items that would be paid for right now. */
    public int sellableItems(Spawner spawner) {
        if (spawner == null) return 0;
        return price(spawner.contents(), prices()).items();
    }

    /**
     * Sells a spawner's priced storage to the clicking player.
     *
     * <p>Runs the same quote/pay/remove sequence as autosell so a manual click can
     * never pay a different amount than the GUI advertised, and serialises per
     * service so a click and an autosell pass cannot both price the same stack.
     */
    public Sale sell(Player player, Spawner spawner) {
        if (player == null || spawner == null) return Sale.refused(Status.NOTHING_TO_SELL);
        if (!enabled()) return Sale.refused(Status.DISABLED);
        if (!economyReady()) return Sale.refused(Status.NO_ECONOMY);

        Priced priced;
        synchronized (saleLock) {
            priced = price(spawner.contents(), prices());
            if (priced.items() <= 0 || priced.value() <= 0.0) return Sale.refused(Status.NOTHING_TO_SELL);
            if (!pay(player, priced.value())) return Sale.refused(Status.FAILED);
            clear(priced.sold(), spawner);
        }
        return new Sale(Status.SOLD, priced.value(), priced.items());
    }

    /** Money text using the same {@code money-decimals} rules as the rest of the plugin. */
    public String format(double amount) {
        ConfigurationSection money = plugin.getConfig().getConfigurationSection("money-decimals");
        boolean compact = money == null || money.getBoolean("enabled", true);
        boolean applyOnAmount = money == null || money.getBoolean("apply-on-amount", true);
        List<String> formats = new ArrayList<>(6);
        for (int i = 1; i <= 6; i++) {
            String fallback = "%number%" + switch (i) {
                case 1 -> "K";
                case 2 -> "M";
                case 3 -> "B";
                case 4 -> "T";
                case 5 -> "Q";
                default -> "Qt";
            };
            formats.add(money == null ? fallback : money.getString(String.valueOf(i), fallback));
        }
        return Text.money(amount, compact, applyOnAmount, formats);
    }

    /** Cancels the autosell pass when a host wires plugin shutdown explicitly. */
    public void shutdown() {
        ScheduledTask task = autosellTask;
        autosellTask = null;
        if (task != null && !task.isCancelled()) task.cancel();
    }

    // ------------------------------------------------------------ autosell

    private void startAutosell() {
        if (!enabled()) {
            plugin.getLogger().info("Sell feature disabled by config; autosell was not scheduled.");
            return;
        }
        if (!plugin.getConfig().getBoolean("economy.autosell", false)) return;

        long seconds = Math.max(5L, plugin.getConfig().getLong("sell.autosell-interval-seconds", 60L));
        long ticks = Math.max(MIN_AUTOSELL_TICKS, seconds * 20L);
        try {
            autosellTask = Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, task -> autosellPass(), ticks, ticks);
            plugin.getLogger().info("Autosell enabled — every " + seconds + "s; payouts go through Vault"
                    + (shop.available() ? " (shop hook: " + shop.detectedPlugin() + ")" : "") + ".");
        } catch (RuntimeException e) {
            plugin.getLogger().warning("could not schedule autosell: " + e.getMessage());
        }
    }

    private void autosellPass() {
        if (!plugin.isEnabled() || !enabled()) return;
        var manager = plugin.manager();
        if (manager == null) return;
        if (!economyReady()) return;

        PriceTable table = prices();
        for (Spawner spawner : manager.all()) {
            if (spawner == null) continue;
            try {
                autosellSpawner(spawner, table);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("autosell failed for " + spawner.id() + ": " + e.getMessage());
            }
        }
    }

    private void autosellSpawner(Spawner spawner, PriceTable table) {
        Priced priced;
        synchronized (saleLock) {
            priced = price(spawner.contents(), table);
            if (priced.items() <= 0 || priced.value() <= 0.0) return;
            OfflinePlayer owner = Bukkit.getOfflinePlayer(spawner.owner());
            if (!pay(owner, priced.value())) return;
            clear(priced.sold(), spawner);
        }
        notifyOwner(spawner, priced);
    }

    /**
     * Tells the owner only when they asked for it and are online.
     *
     * <p>The message is dispatched through the entity scheduler: on Folia the
     * autosell pass runs on the global region thread, which does not own the
     * player and must not talk to the connection directly.
     */
    private void notifyOwner(Spawner spawner, Priced priced) {
        if (plugin.getConfig().getBoolean("disable-autosell-notifications", false)) return;
        Player owner = Bukkit.getPlayer(spawner.owner());
        if (owner == null || !owner.isOnline()) return;

        String text = "&aAutosold &f" + priced.items() + " &aitem(s) for &f"
                + format(priced.value()) + "&a.";
        owner.getScheduler().execute(plugin, () -> owner.sendMessage(Text.of(text)), null, 1L);
    }

    // ------------------------------------------------------------- pricing

    /**
     * Reads the live config on every call: {@code reloadConfig()} swaps the object,
     * so a cached table would silently keep paying last boot's prices.
     */
    private PriceTable prices() {
        Map<String, Double> units = new HashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("sell-prices");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                double price = section.getDouble(key, -1.0);
                if (price > 0.0 && Double.isFinite(price)) units.put(key.toUpperCase(Locale.ROOT), price);
            }
        }

        double multiplier = plugin.getConfig().getDouble("economy.sell-multiplier", 1.0);
        if (!Double.isFinite(multiplier)) multiplier = 1.0;
        multiplier = Math.max(0.0, multiplier);
        return new PriceTable(Map.copyOf(units), multiplier);
    }

    private Priced price(List<ItemStack> contents, PriceTable table) {
        List<ItemStack> sold = new ArrayList<>();
        int items = 0;
        double total = 0.0;

        for (ItemStack stack : contents) {
            if (stack == null || stack.getType().isAir()) continue;
            Double unit = table.unitPrices().get(stack.getType().name());
            if (unit == null) continue;
            ItemStack copy = stack.clone();
            sold.add(copy);
            items += copy.getAmount();
            total += unit * copy.getAmount();
        }
        return new Priced(sold, items, total * table.multiplier());
    }

    /**
     * Removes exactly the priced stacks, leaving anything produced after the quote.
     *
     * <p>Re-reads the storage instead of clearing blindly so an autosell pass that
     * overlapped a region tick cannot destroy loot it never paid for; the loop
     * consumes the sold amounts across every matching live stack. Only the amount
     * quoted is removed — an extra stack of the same material stays behind in full.
     */
    private void clear(List<ItemStack> sold, Spawner spawner) {
        List<ItemStack> live = spawner.contents();
        List<ItemStack> remaining = new ArrayList<>(live.size());

        for (ItemStack stack : live) {
            int keep = stack.getAmount();
            for (ItemStack target : sold) {
                if (keep <= 0) break;
                if (!target.isSimilar(stack)) continue;
                int take = Math.min(keep, target.getAmount());
                target.setAmount(target.getAmount() - take);
                keep -= take;
            }
            if (keep > 0) {
                ItemStack copy = stack.clone();
                copy.setAmount(keep);
                remaining.add(copy);
            }
        }
        spawner.replaceContents(remaining);
    }

    /**
     * Pays through Vault and reports whether the money actually left the bank.
     *
     * <p>Vault's deposit call has no result object, so a thrown provider exception
     * is the only observable failure; treating it as "not paid" keeps the storage
     * untouched instead of deleting loot for money that never arrived.
     */
    private boolean pay(OfflinePlayer target, double amount) {
        if (plugin.economy() == null || !plugin.economy().enabled()) return false;
        try {
            plugin.economy().deposit(target, amount);
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("deposit of " + format(amount) + " failed: " + e.getMessage());
            return false;
        }
    }

    private boolean economyReady() {
        if (plugin.economy() != null && plugin.economy().enabled()) {
            economyWarned.set(false);
            return true;
        }
        if (economyWarned.compareAndSet(false, true)) {
            plugin.getLogger().warning("Sell skipped: no Vault economy provider is registered; "
                    + "stored items were left untouched.");
        }
        return false;
    }
}
