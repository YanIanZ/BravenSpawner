package dev.yanianz.bravenspawner.sell;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Escrow;
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
 * <p>Every sale is escrowed before it is paid (STOR-01, VSS-05). Under the spawner
 * monitor the priced quantities move from storage into an {@link Escrow} inside the
 * spawner record; the record is then saved; only after that save is confirmed is
 * the economy asked to pay, back on the payee's (or the global) thread. A confirmed
 * deposit drops the escrow, a refusal returns its units to storage. A crash
 * therefore never duplicates value: before the save nothing changed on disk, after
 * it the units sit in an escrow whose payment outcome is unknown, which is held for
 * an administrator ({@code /bspawner escrow}) instead of being re-sold or returned.
 *
 * <p>Loot produced between the quote and the commit stays in storage: only the
 * priced quantities are escrowed, by identity.
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
    public enum Status { SOLD, NOTHING_TO_SELL, DISABLED, NO_ECONOMY, FAILED, PENDING, SETTLING }

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
    private record Reservation(ItemStack template, long amount) {}

    private record Priced(List<Reservation> reserved, long items, double value) {}

    /** A shop-plugin unit quote for one item identity, fetched before the spawner lock. */
    private record ShopQuote(ItemStack template, double unit) {}

    private record PriceTable(Map<String, Double> unitPrices, double multiplier, Player viewer,
                              List<ShopQuote> shopQuotes, int roundTo, Currency currency) {
        PriceTable withMultiplier(double value) {
            return new PriceTable(unitPrices, value, viewer, shopQuotes, roundTo, currency);
        }
    }

    /** Runs settlement steps on the thread that owns them; replaceable in tests. */
    interface Dispatcher {
        /** Runs on the player's entity thread; false when the entity refused the task. */
        boolean onPlayer(Player player, Runnable task, Runnable retired);
        /** Runs on the global region (the main thread on Paper); false when refused. */
        boolean onGlobal(Runnable task);
    }

    /** Durable save of everything marked dirty so far; replaceable in tests. */
    interface Durability {
        java.util.concurrent.CompletableFuture<Boolean> persistSoon();
    }

    private static final int WAITING = 0, PAYING = 1, DONE = 2;

    /** An escrowed sale between its save and its payment; the state is claimed exactly once. */
    private record InFlight(Spawner spawner, Escrow escrow, java.util.concurrent.atomic.AtomicInteger state) {}

    private static final long MIN_AUTOSELL_TICKS = 100L;

    private static volatile SellService instance;

    private final BravenSpawner plugin;
    private final ShopHook shop;
    private final AtomicBoolean economyWarned = new AtomicBoolean();

    /** Escrowed sales whose payment has not finished, keyed by escrow id. */
    private final Map<UUID, InFlight> inFlight = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Dispatcher dispatcher;
    private volatile Durability durability;

    /** One queued or running autosale per spawner, released even when an entity retires. */
    private final Map<UUID, Object> queuedAutosells = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean stopped;
    private volatile ScheduledTask autosellTask;

    private SellService(BravenSpawner plugin) {
        this.plugin = plugin;
        this.dispatcher = new Dispatcher() {
            @Override public boolean onPlayer(Player player, Runnable task, Runnable retired) {
                return player.getScheduler().execute(plugin, task, retired, 1L);
            }
            @Override public boolean onGlobal(Runnable task) {
                Bukkit.getGlobalRegionScheduler().execute(plugin, task);
                return true;
            }
        };
        this.durability = () -> {
            var checkpoints = plugin.checkpoints();
            return checkpoints == null ? java.util.concurrent.CompletableFuture.completedFuture(false)
                    : checkpoints.persistSoon();
        };
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

    /** Settles the complete wand-adjusted quote with one escrow and one deposit. */
    public Sale sell(Player player, Spawner spawner, double multiplier) {
        return sell(player, spawner, multiplier, null);
    }

    /**
     * Sells and reports the outcome.
     *
     * <p>A refusal, or a settlement that finished before this method returned, is the
     * return value and {@code settled} is not called. Otherwise the return value is
     * {@link Status#SETTLING} and {@code settled} receives the final outcome later, on
     * the player's thread.
     */
    public Sale sell(Player player, Spawner spawner, double multiplier, java.util.function.Consumer<Sale> settled) {
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
        return attemptSale(spawner, player, base.withMultiplier(combined), player, player, settled);
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
        // Sales still waiting for their save were never paid: return their units so
        // the final drain stores them. A payment already in progress stays escrowed.
        // Their callbacks are not invoked: during disable they would message players
        // and play sounds off the owning threads. Wand uses were charged at the click.
        int returned = 0;
        for (InFlight flight : List.copyOf(inFlight.values())) {
            if (!flight.state().compareAndSet(WAITING, DONE)) continue;
            inFlight.remove(flight.escrow().id(), flight);
            if (flight.spawner().refund(flight.escrow().id())) {
                plugin.markDirty(flight.spawner());
                returned++;
            }
        }
        if (returned > 0) plugin.getLogger().info(returned + " unpaid sale(s) returned to storage at shutdown.");
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
        // An owner who left the island (or, with Aurora MultiProfile, is on another
        // profile) must not be paid for its spawners; the stock stays until they return.
        var islands = plugin.islands();
        if (islands != null && islands.requiresMembershipForAutosell()) {
            // An unloaded island world (AWF/ASP idle unload) cannot be checked: wait
            // for it to load instead of treating it as "not on an island".
            var location = spawner.blockLocation();
            if (location == null || !islands.member(owner, location)) return false;
        }
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
            // One autosale in flight per spawner; the next pass picks up the rest.
            if (settling(spawner)) return;
        }
        sale = attemptSale(spawner, owner, table, null, online, done -> {
            if (done.status() == Status.SOLD) notifyOwner(spawner, done.items(), done.value());
        });
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

    /** True while a sale of this spawner is between its escrow and its payment. */
    public boolean settling(Spawner spawner) {
        for (InFlight flight : inFlight.values()) if (flight.spawner() == spawner) return true;
        return false;
    }

    /**
     * Escrows held by registered spawners that no live sale owns: their payment
     * outcome was unknown at the last shutdown or crash. Each awaits an
     * administrator's {@code /bspawner escrow refund|confirm}.
     */
    public List<Map.Entry<Spawner, Escrow>> unresolved() {
        List<Map.Entry<Spawner, Escrow>> out = new ArrayList<>();
        var manager = plugin.manager();
        if (manager == null) return out;
        for (Spawner spawner : manager.all()) {
            for (Escrow escrow : spawner.escrows()) {
                if (!inFlight.containsKey(escrow.id())) out.add(Map.entry(spawner, escrow));
            }
        }
        return out;
    }

    /**
     * Phase A: quote and escrow under the spawner monitor, then request a durable save.
     *
     * @param payOn player whose thread pays (null pays on the global thread)
     */
    private Sale attemptSale(Spawner spawner, OfflinePlayer target, PriceTable table, Player actor, Player payOn,
                             java.util.function.Consumer<Sale> settled) {
        Escrow escrow;
        InFlight flight;
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
            if (target == null) return Sale.refused(Status.FAILED, "no payout target");
            List<StockEntry> requests = new ArrayList<>(priced.reserved().size());
            for (Reservation reservation : priced.reserved()) {
                requests.add(new StockEntry(reservation.template(), reservation.amount()));
            }
            escrow = spawner.hold(UUID.randomUUID(), target.getUniqueId(), table.currency().id(), priced.value(), requests);
            if (escrow == null) {
                return new Sale(Status.PENDING, 0.0, 0, "too many sales of this spawner are still settling");
            }
            flight = new InFlight(spawner, escrow, new java.util.concurrent.atomic.AtomicInteger(WAITING));
            inFlight.put(escrow.id(), flight);
            plugin.markDirty(spawner);
        }

        java.util.concurrent.atomic.AtomicReference<Sale> immediate = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean returned = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<Sale> finish = outcome -> {
            synchronized (immediate) {
                if (!returned.get()) { immediate.set(outcome); return; }
            }
            if (settled != null) {
                try { settled.accept(outcome); }
                catch (RuntimeException e) { plugin.getLogger().warning("sale callback failed: " + e.getMessage()); }
            }
        };

        java.util.concurrent.CompletableFuture<Boolean> saved;
        try {
            saved = durability.persistSoon();
        } catch (RuntimeException e) {
            saved = java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        saved.whenComplete((ok, error) -> dispatchPayment(flight, Boolean.TRUE.equals(ok) && error == null,
                target, table.currency(), actor, payOn, finish));

        synchronized (immediate) {
            returned.set(true);
            Sale done = immediate.get();
            if (done != null) return done;
        }
        return new Sale(Status.SETTLING, escrow.value(), escrow.quantity(), "");
    }

    /** Hops from the save callback to the thread that may pay. */
    private void dispatchPayment(InFlight flight, boolean saved, OfflinePlayer target, Currency currency,
                                 Player actor, Player payOn, java.util.function.Consumer<Sale> finish) {
        Runnable pay = () -> pay(flight, saved, target, currency, actor, finish);
        Runnable retired = () -> returnUnpaid(flight, finish, "the player left before the payment");
        try {
            boolean accepted = payOn != null ? dispatcher.onPlayer(payOn, pay, retired) : dispatcher.onGlobal(pay);
            if (!accepted) retired.run();
        } catch (RuntimeException e) {
            returnUnpaid(flight, finish, "the payment could not be scheduled");
        }
    }

    /** Phase B: runs on the payee's (or global) thread after the escrow save. */
    private void pay(InFlight flight, boolean saved, OfflinePlayer target, Currency currency, Player actor,
                     java.util.function.Consumer<Sale> finish) {
        Spawner spawner = flight.spawner();
        Escrow escrow = flight.escrow();
        if (!saved) {
            returnUnpaid(flight, finish, "the sale could not be saved, so nothing was paid");
            return;
        }
        if (stopped || plugin.manager() == null || plugin.manager().byId(spawner.id()) != spawner) {
            returnUnpaid(flight, finish, "the spawner is no longer available");
            return;
        }
        if (actor != null && !actor.isOnline()) {
            returnUnpaid(flight, finish, "the player left before the payment");
            return;
        }
        // From here on the outcome may be unknown after a crash; never refund twice.
        if (!flight.state().compareAndSet(WAITING, PAYING)) return;
        String refusal = deposit(currency, target, escrow.value());
        synchronized (spawner) {
            if (refusal == null) spawner.release(escrow.id());
            else spawner.refund(escrow.id());
        }
        flight.state().set(DONE);
        inFlight.remove(escrow.id(), flight);
        plugin.markDirty(spawner);
        if (refusal != null) {
            finish.accept(new Sale(Status.FAILED, escrow.value(), escrow.quantity(), refusal));
            return;
        }
        recordSettlement(spawner, escrow, actor);
        finish.accept(new Sale(Status.SOLD, escrow.value(), escrow.quantity(), ""));
    }

    /** Returns a never-paid escrow to storage; a no-op once payment was claimed. */
    private void returnUnpaid(InFlight flight, java.util.function.Consumer<Sale> finish, String reason) {
        if (!flight.state().compareAndSet(WAITING, DONE)) return;
        inFlight.remove(flight.escrow().id(), flight);
        Spawner spawner = flight.spawner();
        if (spawner.refund(flight.escrow().id())) plugin.markDirty(spawner);
        finish.accept(new Sale(Status.FAILED, flight.escrow().value(), flight.escrow().quantity(), reason));
    }

    /** Recorded once, when the deposit was confirmed. */
    private void recordSettlement(Spawner spawner, Escrow escrow, Player actor) {
        try {
            plugin.record(spawner, dev.yanianz.bravenspawner.audit.AuditEvent.Action.SELL,
                    actor, escrow.quantity(), "value=" + escrow.value() + "; sale=" + escrow.id());
        } catch (RuntimeException exception) {
            // Audit I/O must never turn an already committed payout into a retry.
            plugin.getLogger().warning("could not record sale " + escrow.id() + ": " + exception.getMessage());
        }
    }

    /** Test hook: replaces thread dispatch and durability. */
    void testHooks(Dispatcher dispatcher, Durability durability) {
        if (dispatcher != null) this.dispatcher = dispatcher;
        if (durability != null) this.durability = durability;
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
