package dev.yanianz.bravenspawner.sell;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.model.StockEntry;
import dev.yanianz.bravenspawner.util.Schedulers;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import dev.yanianz.bravenspawner.econ.Currency;
import dev.yanianz.bravenspawner.econ.CurrencyAction;
import dev.yanianz.bravenspawner.econ.CurrencyMath;
import dev.yanianz.bravenspawner.econ.CurrencyRegistry;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Converts stored spawner loot into money, on demand and on a timer.
 *
 * <p>Every sale is a small state machine — {@code QUOTED → PAID → CLEARED}, or
 * {@code QUOTED → FAILED} — so a quote can be deposited exactly once and its
 * stock removed exactly once. A quote that was paid but whose slots could not be
 * drained is quarantined per spawner and retried before any new quote, which is
 * what stops a retry from paying the same stack twice.
 *
 * <p>Removal is a reservation drain, not a snapshot replace: the priced amount
 * of each material is taken from live storage with {@link Spawner#drainSlot(int, int)},
 * so loot produced between the quote and the commit survives. Only the exact
 * amounts that were paid for are ever removed.
 *
 * <p>The service is a per-plugin singleton installed from the GUI constructor,
 * which is the only subsystem the bootstrap creates during onEnable; that keeps
 * autosell scheduled at boot without wiring changes elsewhere. The repeating pass
 * uses the global region scheduler only to dispatch. Online owners are quoted and
 * paid on their entity scheduler; offline settlement is restricted to Paper.
 *
 * <p>Sales are serialised per spawner (its monitor), not by one service-wide lock,
 * so a slow shop or Vault provider on one spawner never stalls sales of every
 * other spawner. Shop-plugin quotes for the distinct item identities are fetched
 * before the spawner monitor is taken, so provider pricing calls do not run while
 * production on that spawner is blocked.
 *
 * <p>Payouts are rounded HALF_UP to {@code economy.round-to} decimals (default 2)
 * when quoted, so the shown value, the deposit and the audit record always agree.
 */
public final class SellService {

    /** Outcome of a sale attempt, so the caller can word the feedback without re-checking config. */
    public enum Status { SOLD, NOTHING_TO_SELL, DISABLED, NO_ECONOMY, FAILED, PENDING }

    /** A completed, refused or quarantined sale: value is what was (or would have been) paid. */
    public record Sale(Status status, double value, long items, String reason) {
        static Sale refused(Status status, String reason) {
            return new Sale(status, 0.0, 0, reason);
        }
    }

    /**
     * One priced amount to remove. The template is the quoted identity (material
     * plus item meta), so two stacks of the same material with different meta are
     * never conflated and the drain cannot remove a different item than it priced.
     */
    private static final class Reservation {
        private final ItemStack template;
        private long remaining;

        private Reservation(ItemStack template, long amount) {
            this.template = template;
            this.remaining = amount;
        }
    }

    private record Priced(List<Reservation> reserved, long items, double value) {}

    /** A shop-plugin unit quote for one item identity, fetched before the spawner lock. */
    private record ShopQuote(ItemStack template, double unit) {}

    private record PriceTable(Map<String, Double> unitPrices, double multiplier, Player viewer,
                              List<ShopQuote> shopQuotes, int roundTo, Currency currency) {
        PriceTable withMultiplier(double value) {
            return new PriceTable(unitPrices, value, viewer, shopQuotes, roundTo, currency);
        }
    }

    /** The only legal transitions: QUOTED→PAID→CLEARED, or QUOTED→FAILED. */
    private enum Phase { QUOTED, PAID, CLEARED, FAILED }

    /**
     * One sale attempt carrying the quote it must pay for.
     *
     * <p>{@link #markPaid()} and {@link #markCleared()} refuse a second call, so
     * even a buggy caller cannot turn one quote into two deposits or two drains.
     * Methods are synchronized because the autosell pass and a GUI click share
     * the spawner lock, but a future async settlement must not see a half state.
     */
    private static final class SaleAttempt {
        private final UUID id = UUID.randomUUID();
        private final UUID spawnerId;
        private final List<Reservation> reservations;
        private final long items;
        private final double value;
        private final Player actor;
        private Phase phase = Phase.QUOTED;

        private SaleAttempt(UUID spawnerId, List<Reservation> reservations, long items, double value, Player actor) {
            this.spawnerId = spawnerId;
            this.reservations = reservations;
            this.items = items;
            this.value = value;
            this.actor = actor;
        }

        synchronized boolean markPaid() {
            if (phase != Phase.QUOTED) return false;
            phase = Phase.PAID;
            return true;
        }

        synchronized boolean markCleared() {
            if (phase != Phase.PAID) return false;
            phase = Phase.CLEARED;
            return true;
        }

        synchronized void failBeforePayment() {
            if (phase == Phase.QUOTED) phase = Phase.FAILED;
        }

        /** Units still owed to the buyer; shrinks as the drain succeeds. */
        synchronized long outstanding() {
            long left = 0;
            for (Reservation reservation : reservations) left += Math.max(0, reservation.remaining);
            return left;
        }
    }

    private static final long MIN_AUTOSELL_TICKS = 100L;

    private static volatile SellService instance;

    private final BravenSpawner plugin;
    private final ShopHook shop;
    private final AtomicBoolean economyWarned = new AtomicBoolean();

    /** Paid-but-uncleared attempts, keyed by spawner; each entry is guarded by its spawner's monitor. */
    private final Map<UUID, SaleAttempt> pendingPayouts = new java.util.concurrent.ConcurrentHashMap<>();

    /** One queued or running autosale per spawner, released even when an entity retires. */
    private final Map<UUID, Object> queuedAutosells = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean stopped;
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
        if (local != null && local.plugin == plugin && !local.stopped) return local;
        synchronized (SellService.class) {
            if (instance == null || instance.plugin != plugin || instance.stopped) {
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
        return price(spawner.stock(), prices(spawner)).value();
    }

    public double value(Player viewer, Spawner spawner) {
        return spawner == null ? 0.0 : price(spawner.stock(), prices(spawner, viewer)).value();
    }
    public long sellableItems(Player viewer, Spawner spawner) {
        return spawner == null ? 0 : price(spawner.stock(), prices(spawner, viewer)).items();
    }

    /** Number of stored items that would be paid for right now. */
    public long sellableItems(Spawner spawner) {
        if (spawner == null) return 0;
        return price(spawner.stock(), prices(spawner)).items();
    }

    /**
     * Sells a spawner's priced storage to the clicking player.
     *
     * <p>Runs the same quote/pay/drain sequence as autosell so a manual click can
     * never pay a different amount than the GUI advertised, and serialises per
     * spawner so a click and an autosell pass cannot both price the same stack.
     * A previous payout that is still waiting for stock is finished first — that
     * is the retry path, and it never calls the economy again.
     */
    public Sale sell(Player player, Spawner spawner) {
        return sell(player, spawner, 1.0);
    }

    /** Settles the complete wand-adjusted quote with one deposit and one drain. */
    public Sale sell(Player player, Spawner spawner, double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            return Sale.refused(Status.FAILED, "invalid sale multiplier");
        }
        if (player == null || spawner == null) {
            return Sale.refused(Status.NOTHING_TO_SELL, "no spawner selected");
        }
        if (plugin.restrictions() != null && !plugin.restrictions().canOpen(player, spawner))
            return Sale.refused(Status.DISABLED, "spawner access is restricted");
        if (plugin.protection() != null && !plugin.protection().canUse(player, spawner.blockLocation()))
            return Sale.refused(Status.DISABLED, "claim protection denied this sale");
        if (!enabled()) return Sale.refused(Status.DISABLED, "selling is disabled by config");
        Currency currency = currency();
        if (!economyReady(currency)) return Sale.refused(Status.NO_ECONOMY, currency.unavailableReason());

        PriceTable base = prices(spawner, player, currency);
        double combined = base.multiplier() * multiplier;
        if (!Double.isFinite(combined)) return Sale.refused(Status.FAILED, "sale multiplier overflow");
        synchronized (spawner) {
            Sale outstanding = settleOutstanding(spawner);
            if (outstanding != null) return outstanding;
            return attemptSale(spawner, player, base.withMultiplier(combined), player);
        }
    }

    /**
     * Payout text in the sell currency: {@code economy.format.<currency>} symbol and
     * suffix around the shared {@code money-decimals} compaction.
     */
    public String format(double amount) {
        return currency().format(amount);
    }

    /** The currency sales pay out in ({@code economy.currencies.sell}); never null. */
    public Currency currency() {
        return CurrencyRegistry.select(plugin, CurrencyAction.SELL);
    }

    /** Stop only an existing service; shutdown must not install a new singleton. */
    public static void stop(BravenSpawner plugin) {
        SellService current = instance;
        if (current != null && current.plugin == plugin) current.shutdown();
    }

    /** Cancels dispatch and invalidates queued callbacks before storage shuts down. */
    public void shutdown() {
        stopped = true;
        queuedAutosells.clear();
        ScheduledTask task = autosellTask;
        autosellTask = null;
        if (task != null && !task.isCancelled()) task.cancel();
        if (!pendingPayouts.isEmpty()) {
            plugin.getLogger().warning(pendingPayouts.size()
                    + " paid sale(s) still had unremoved stock at shutdown; they were not paid again.");
        }
        pendingPayouts.clear();
    }

    // ------------------------------------------------------------ autosell

    private void startAutosell() {
        if (!enabled()) {
            plugin.getLogger().info("Sell feature disabled by config; autosell was not scheduled.");
            return;
        }
        if (!plugin.getConfig().getBoolean("economy.autosell", false)
                && !plugin.getConfig().getBoolean("enable-boosters", true)) return;

        long seconds = Math.max(5L, plugin.getConfig().getLong("sell.autosell-interval-seconds", 60L));
        long ticks = Math.max(MIN_AUTOSELL_TICKS, seconds * 20L);
        try {
            autosellTask = Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, task -> autosellPass(), ticks, ticks);
            plugin.getLogger().info("Autosell enabled — every " + seconds
                    + "s; online owners settle on their entity scheduler; offline settlement is Paper-only.");
        } catch (RuntimeException e) {
            plugin.getLogger().warning("could not schedule autosell: " + e.getMessage());
        }
    }

    private void autosellPass() {
        if (stopped || !plugin.isEnabled() || !plugin.ready() || !enabled()) return;
        var manager = plugin.manager();
        if (manager == null) return;
        for (Spawner spawner : manager.all()) {
            if (spawner == null) continue;
            try {
                dispatchAutosell(spawner);
            } catch (RuntimeException e) {
                logAutosellFailure(spawner, e);
            }
        }
    }

    private boolean eligible(Spawner spawner, UUID owner) {
        if (stopped || !plugin.isEnabled() || !plugin.ready() || !enabled()) return false;
        var manager = plugin.manager();
        if (manager == null || manager.byId(spawner.id()) != spawner || !owner.equals(spawner.owner())) return false;
        if (!plugin.getConfig().getBoolean("economy.autosell", false)
                && (plugin.boosters() == null || !plugin.boosters().autosell(owner, spawner.id()))) return false;
        return plugin.restrictions() == null || plugin.restrictions().rules().canOpen(spawner.product(), false);
    }

    private void dispatchAutosell(Spawner spawner) {
        UUID ownerId = spawner.owner();
        if (ownerId == null || !eligible(spawner, ownerId)) return;
        Object ticket = new Object();
        if (queuedAutosells.putIfAbsent(spawner.id(), ticket) != null) return;
        Runnable release = () -> queuedAutosells.remove(spawner.id(), ticket);
        try {
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner == null) {
                try {
                    // Folia has no universal thread on which arbitrary offline providers are safe.
                    // Keep the stock until the owner returns instead of guessing a provider context.
                    if (!Schedulers.isFolia()) autosellSpawner(spawner, ownerId, null);
                } finally {
                    release.run();
                }
                return;
            }
            boolean accepted = owner.getScheduler().execute(plugin, () -> {
                try {
                    if (queuedAutosells.get(spawner.id()) == ticket) autosellSpawner(spawner, ownerId, owner);
                } catch (RuntimeException failure) {
                    logAutosellFailure(spawner, failure);
                } finally {
                    release.run();
                }
            }, release, 1L);
            // A retired scheduler returns false without invoking either callback.
            if (!accepted) release.run();
        } catch (RuntimeException failure) {
            release.run();
            throw failure;
        }
    }

    private void logAutosellFailure(Spawner spawner, RuntimeException failure) {
        plugin.getLogger().warning("autosell failed for " + spawner.id() + ": " + failure.getMessage());
    }

    private void autosellSpawner(Spawner spawner, UUID ownerId, Player online) {
        if (!eligible(spawner, ownerId)) return;
        if (online != null && (!online.isOnline() || Bukkit.getPlayer(ownerId) != online)) return;
        Currency currency = currency();
        if (online == null && currency.requiresOnline()) return;
        if (!economyReady(currency)) return;
        OfflinePlayer owner = online == null ? Bukkit.getOfflinePlayer(ownerId) : online;
        // Preserve existing autosell prices (no manual-sale personal boost), but query shops
        // only after dispatch to the owner's thread. Offline Paper still uses the main thread.
        PriceTable table = prices(spawner, null, currency);
        Sale sale;
        synchronized (spawner) {
            if (!eligible(spawner, ownerId) || (online != null && !online.isOnline())) return;
            Sale outstanding = settleOutstanding(spawner);
            if (outstanding != null) {
                if (outstanding.status() == Status.PENDING) {
                    plugin.getLogger().warning("payout for " + spawner.id()
                            + " is still waiting for stock; autosell skipped it.");
                }
                return;
            }
            sale = attemptSale(spawner, owner, table, null);
        }
        if (sale.status() == Status.SOLD) notifyOwner(spawner, sale.items(), sale.value());
    }

    /**
     * Tells the owner only when they asked for it and are online.
     *
     * <p>The message is dispatched through the entity scheduler because
     * offline Paper and online entity dispatch use different entry points; keeping
     * notification entity-owned also handles a player moving between regions.
     */
    private void notifyOwner(Spawner spawner, long items, double value) {
        if (plugin.getConfig().getBoolean("disable-autosell-notifications", false)) return;
        Player owner = Bukkit.getPlayer(spawner.owner());
        if (owner == null || !owner.isOnline()) return;

        String amount = String.format(java.util.Locale.ROOT, "%,d", items);
        String worth = format(value);
        owner.getScheduler().execute(plugin, () -> dev.yanianz.bravenspawner.util.Messages.of(plugin)
                .send(owner, "economy.autosold", "items", amount, "value", worth), null, 1L);
    }

    // ------------------------------------------------------------- pricing

    /**
     * Reads the live config on every call: {@code reloadConfig()} swaps the object,
     * so a cached table would silently keep paying last boot's prices.
     */
    private PriceTable prices(Spawner spawner) { return prices(spawner, null, currency()); }

    private PriceTable prices(Spawner spawner, Player viewer) { return prices(spawner, viewer, currency()); }

    private PriceTable prices(Spawner spawner, Player viewer, Currency currency) {
        Map<String, Double> units = new HashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("sell-prices");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                double price = section.getDouble(key, -1.0);
                if (price > 0.0 && Double.isFinite(price)) units.put(key.toUpperCase(Locale.ROOT), price);
            }
        }

        // An explicit product zero disables selling that drop; an unset (-1)
        // price inherits the global default. Products can price drops absent there.
        var product = plugin.product(spawner);
        if (product != null) {
            for (var drop : product.drops()) {
                double value = drop.sellPrice();
                if (Double.isFinite(value) && value >= 0.0) {
                    units.put(drop.material().name(), value);
                }
            }
        }

        double multiplier = plugin.getConfig().getDouble("economy.sell-multiplier", 1.0);
        if (!Double.isFinite(multiplier)) multiplier = 1.0;
        multiplier = Math.max(0.0, multiplier);
        if (plugin.events() != null) multiplier *= plugin.events().cashMultiplier();
        if (viewer != null && plugin.boosters() != null) multiplier *= plugin.boosters().personalMultiplier(
                dev.yanianz.bravenspawner.boosters.Boosters.Type.CASH, viewer.getUniqueId());
        return new PriceTable(Map.copyOf(units), multiplier, viewer, shopQuotes(spawner, viewer), roundTo(), currency);
    }

    /**
     * Shop-plugin unit prices for each distinct identity currently stored, fetched
     * without holding the spawner monitor. Identities that appear later are quoted
     * inline by {@link #unitPrice}.
     */
    private List<ShopQuote> shopQuotes(Spawner spawner, Player viewer) {
        if (plugin.shops() == null) return List.of();
        List<ShopQuote> quotes = new ArrayList<>();
        for (StockEntry entry : spawner.stock()) {
            ItemStack stack = entry.template();
            boolean known = false;
            for (ShopQuote quote : quotes) {
                if (quote.template().isSimilar(stack)) { known = true; break; }
            }
            if (!known) quotes.add(new ShopQuote(stack.clone(), plugin.shops().priceOf(viewer, stack)));
        }
        return List.copyOf(quotes);
    }

    private Double unitPrice(PriceTable table, ItemStack stack) {
        Double unit = table.unitPrices().get(stack.getType().name());
        if (plugin.shops() == null) return unit;
        double quote = Double.NaN;
        boolean cached = false;
        for (ShopQuote candidate : table.shopQuotes()) {
            if (candidate.template().isSimilar(stack)) { quote = candidate.unit(); cached = true; break; }
        }
        if (!cached) quote = plugin.shops().priceOf(table.viewer(), stack);
        return Double.isFinite(quote) && quote >= 0.0 ? Double.valueOf(quote) : unit;
    }

    /** Decimal places for payouts; {@code economy.round-to}, default 2, clamped to 0..6. */
    private int roundTo() {
        return Math.max(0, Math.min(6, plugin.getConfig().getInt("economy.round-to", 2)));
    }

    /** HALF_UP to {@code decimals} places; non-finite values pass through for the caller to refuse. */
    static double round(double value, int decimals) {
        if (!Double.isFinite(value)) return value;
        return java.math.BigDecimal.valueOf(value)
                .setScale(decimals, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Prices the priced part of storage and turns it into per-identity reservations.
     *
     * <p>Aggregating similar stacks here means the commit removes a quantity, not
     * a set of storage positions: positions shift whenever a producer appends and
     * a position-based quote would either miss loot or take the wrong stack.
     */
    private Priced price(List<StockEntry> stock, PriceTable table) {
        List<Reservation> reserved = new ArrayList<>();
        long items = 0;
        double total = 0.0;

        // Storage already keeps one entry per identity, so each priced entry is
        // exactly one reservation of its exact quantity.
        for (StockEntry entry : stock) {
            ItemStack identity = entry.template();
            Double unit = unitPrice(table, identity);
            if (unit == null || unit <= 0.0) continue;
            long amount = entry.quantity();
            reserved.add(new Reservation(identity, amount));
            items = saturatingAdd(items, amount);
            total += unit * (double) amount;
        }
        // Whole-unit currencies (points, levels, items) round the payout down.
        return new Priced(List.copyOf(reserved), items,
                CurrencyMath.payout(total * table.multiplier(), table.roundTo(), table.currency().wholeUnits()));
    }

    private static long saturatingAdd(long left, long right) {
        long sum = left + right;
        return ((left ^ sum) & (right ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    // ---------------------------------------------------------- settlement

    /**
     * Finishes a previously paid quote before a new one is quoted.
     *
     * <p>Returns null when there is nothing pending or the pending drain has just
     * completed, so the caller may quote the remaining stock. Returns a
     * {@link Status#PENDING} sale while the stock is still owed: the money already
     * left the provider, and quoting again would pay for the same units twice.
     */
    private Sale settleOutstanding(Spawner spawner) {
        synchronized (spawner) {
            SaleAttempt pending = pendingPayouts.get(spawner.id());
            if (pending == null) return null;
            if (drain(pending, spawner) && pending.markCleared()) {
                pendingPayouts.remove(spawner.id(), pending);
                recordSettlement(spawner, pending);
                plugin.getLogger().info("reconciled paid sale " + pending.id + " for spawner " + spawner.id());
                return null;
            }
            return new Sale(Status.PENDING, pending.value, pending.items,
                    "a previous payout of " + format(pending.value) + " is still waiting for its items");
        }
    }

    /**
     * Quotes, pays once and drains once.
     *
     * <p>The order is deliberate: nothing leaves storage until the provider
     * reports a successful deposit. A rejection fails the attempt with storage
     * untouched; a successful deposit followed by a failed drain leaves the
     * attempt in {@link Phase#PAID} and quarantines it, so the next pass removes
     * the stock without calling the economy again.
     */
    private Sale attemptSale(Spawner spawner, OfflinePlayer target, PriceTable table, Player actor) {
        synchronized (spawner) {
            var product = plugin.product(spawner);
            if (product != null && product.mustBeFullToCollect()) {
                long capacity = plugin.maximumItems(spawner);
                if (capacity > 0 && spawner.storedCount() < capacity) {
                    return Sale.refused(Status.NOTHING_TO_SELL, "storage must be full before collecting");
                }
            }
            Priced priced = price(spawner.stock(), table);
            if (!Double.isFinite(priced.value())) return Sale.refused(Status.FAILED, "sale price overflow");
            if (priced.items() <= 0 || priced.value() <= 0.0) {
                return Sale.refused(Status.NOTHING_TO_SELL, "nothing stored has a sell price");
            }

            SaleAttempt attempt = new SaleAttempt(spawner.id(), priced.reserved(), priced.items(), priced.value(), actor);
            String refusal = deposit(table.currency(), target, attempt.value);
            if (refusal != null) {
                attempt.failBeforePayment();
                return new Sale(Status.FAILED, attempt.value, attempt.items, refusal);
            }
            if (!attempt.markPaid()) {
                // Only reachable if a future caller reuses the attempt; refusing here
                // is what keeps one quote from being paid twice.
                return new Sale(Status.FAILED, attempt.value, attempt.items, "sale was already paid");
            }

            if (!drain(attempt, spawner) || !attempt.markCleared()) {
                pendingPayouts.put(spawner.id(), attempt);
                plugin.getLogger().warning("payout " + attempt.id + " for spawner " + spawner.id()
                        + " was sent but " + attempt.outstanding()
                        + " item(s) could not be removed; the sale is quarantined until stock returns.");
                return new Sale(Status.PENDING, attempt.value, attempt.items,
                        "payout sent; " + attempt.outstanding() + " item(s) still to be removed");
            }
            recordSettlement(spawner, attempt);
            return new Sale(Status.SOLD, attempt.value, attempt.items, "");
        }
    }

    /** Called only on the transition to CLEARED, including a later reconciliation. */
    private void recordSettlement(Spawner spawner, SaleAttempt attempt) {
        try {
            plugin.record(spawner, dev.yanianz.bravenspawner.audit.AuditEvent.Action.SELL,
                    attempt.actor, attempt.items, "value=" + attempt.value + "; sale=" + attempt.id);
        } catch (RuntimeException exception) {
            // Audit I/O must never turn an already committed payout into a retry.
            plugin.getLogger().warning("could not record sale " + attempt.id + ": " + exception.getMessage());
        }
    }

    /**
     * Removes exactly the reserved amounts, by identity.
     *
     * <p>{@link Spawner#take(ItemStack, long)} removes from the matching identity
     * atomically under the spawner monitor, so concurrent production appends cannot
     * shift what is removed. Loot that appeared after the quote is not in the
     * reservation and stays put. A shortfall (stock withdrawn by another path after
     * the quote) leaves the attempt paid with its remaining count, retried later.
     */
    private boolean drain(SaleAttempt attempt, Spawner spawner) {
        for (Reservation reservation : attempt.reservations) {
            if (reservation.remaining <= 0) continue;
            long removed = spawner.take(reservation.template, reservation.remaining);
            if (removed <= 0) continue;
            reservation.remaining -= removed;
            plugin.markDirty(spawner);
        }
        return attempt.outstanding() <= 0;
    }

    /**
     * Pays through the sell currency; null means the provider confirmed the credit.
     *
     * <p>A thrown provider exception or a failed result both read as "not paid",
     * which keeps the storage untouched instead of deleting loot for money that
     * never arrived. The returned text is the refusal reason.
     */
    private String deposit(Currency currency, OfflinePlayer target, double amount) {
        if (target == null) return "no payout target";
        if (currency == null || !currency.available()) {
            return currency == null ? "no currency" : currency.unavailableReason();
        }
        try {
            Currency.Result result = currency.deposit(target, amount);
            if (result.success()) return null;
            if (!currency.requiresOnline()) plugin.getLogger().warning(currency.id() + " deposit of " + currency.format(amount) + " to "
                    + target.getUniqueId() + " was rejected: " + result.error());
            return result.error().isBlank() ? currency.displayName() + " rejected the deposit" : result.error();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("deposit of " + format(amount) + " failed: " + e.getMessage());
            return currency.displayName() + " rejected the deposit";
        }
    }

    private boolean economyReady(Currency currency) {
        if (currency != null && currency.available()) {
            economyWarned.set(false);
            return true;
        }
        if (economyWarned.compareAndSet(false, true)) {
            plugin.getLogger().warning("Sell skipped: " + (currency == null ? "no currency"
                    : currency.id() + " is unavailable (" + currency.unavailableReason() + ")")
                    + "; stored items were left untouched.");
        }
        return false;
    }
}
