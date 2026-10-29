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
 * uses the global region scheduler so it runs on Folia as well as Paper.
 */
public final class SellService {

    /** Outcome of a sale attempt, so the caller can word the feedback without re-checking config. */
    public enum Status { SOLD, NOTHING_TO_SELL, DISABLED, NO_ECONOMY, FAILED, PENDING }

    /** A completed, refused or quarantined sale: value is what was (or would have been) paid. */
    public record Sale(Status status, double value, int items, String reason) {
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
        private int remaining;

        private Reservation(ItemStack template, int amount) {
            this.template = template;
            this.remaining = amount;
        }
    }

    private record Priced(List<Reservation> reserved, int items, double value) {}

    private record PriceTable(Map<String, Double> unitPrices, double multiplier) {}

    private record DrainStep(int index, Reservation reservation, int amount) {}

    /** The only legal transitions: QUOTED→PAID→CLEARED, or QUOTED→FAILED. */
    private enum Phase { QUOTED, PAID, CLEARED, FAILED }

    /**
     * One sale attempt carrying the quote it must pay for.
     *
     * <p>{@link #markPaid()} and {@link #markCleared()} refuse a second call, so
     * even a buggy caller cannot turn one quote into two deposits or two drains.
     * Methods are synchronized because the autosell pass and a GUI click share
     * the service lock, but a future async settlement must not see a half state.
     */
    private static final class SaleAttempt {
        private final UUID id = UUID.randomUUID();
        private final UUID spawnerId;
        private final List<Reservation> reservations;
        private final int items;
        private final double value;
        private final Player actor;
        private Phase phase = Phase.QUOTED;

        private SaleAttempt(UUID spawnerId, List<Reservation> reservations, int items, double value, Player actor) {
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
        synchronized int outstanding() {
            int left = 0;
            for (Reservation reservation : reservations) left += Math.max(0, reservation.remaining);
            return left;
        }
    }

    private static final long MIN_AUTOSELL_TICKS = 100L;

    /** A paid quote is retried at most this many times before it stays quarantined. */
    private static final int MAX_DRAIN_PASSES = 4;

    private static volatile SellService instance;

    private final BravenSpawner plugin;
    private final ShopHook shop;
    private final Object saleLock = new Object();
    private final AtomicBoolean economyWarned = new AtomicBoolean();

    /** Paid-but-uncleared attempts, keyed by spawner; guarded by {@link #saleLock}. */
    private final Map<UUID, SaleAttempt> pendingPayouts = new HashMap<>();

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
        return price(spawner.contents(), prices(spawner)).value();
    }

    /** Number of stored items that would be paid for right now. */
    public int sellableItems(Spawner spawner) {
        if (spawner == null) return 0;
        return price(spawner.contents(), prices(spawner)).items();
    }

    /**
     * Sells a spawner's priced storage to the clicking player.
     *
     * <p>Runs the same quote/pay/drain sequence as autosell so a manual click can
     * never pay a different amount than the GUI advertised, and serialises per
     * service so a click and an autosell pass cannot both price the same stack.
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
        if (!enabled()) return Sale.refused(Status.DISABLED, "selling is disabled by config");
        if (!economyReady()) return Sale.refused(Status.NO_ECONOMY, "no Vault economy provider is registered");

        synchronized (saleLock) {
            Sale outstanding = settleOutstanding(spawner);
            if (outstanding != null) return outstanding;
            PriceTable base = prices(spawner);
            double combined = base.multiplier() * multiplier;
            if (!Double.isFinite(combined)) return Sale.refused(Status.FAILED, "sale multiplier overflow");
            return attemptSale(spawner, player, new PriceTable(base.unitPrices(), combined), player);
        }
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
        synchronized (saleLock) {
            if (!pendingPayouts.isEmpty()) {
                plugin.getLogger().warning(pendingPayouts.size()
                        + " paid sale(s) still had unremoved stock at shutdown; they were not paid again.");
            }
            pendingPayouts.clear();
        }
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

        for (Spawner spawner : manager.all()) {
            if (spawner == null) continue;
            try {
                autosellSpawner(spawner);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("autosell failed for " + spawner.id() + ": " + e.getMessage());
            }
        }
    }

    private void autosellSpawner(Spawner spawner) {
        Sale sale;
        synchronized (saleLock) {
            Sale outstanding = settleOutstanding(spawner);
            if (outstanding != null) {
                if (outstanding.status() == Status.PENDING) {
                    plugin.getLogger().warning("payout for " + spawner.id()
                            + " is still waiting for stock; autosell skipped it.");
                }
                return;
            }
            sale = attemptSale(spawner, Bukkit.getOfflinePlayer(spawner.owner()), prices(spawner), null);
        }
        if (sale.status() == Status.SOLD) notifyOwner(spawner, sale.items(), sale.value());
    }

    /**
     * Tells the owner only when they asked for it and are online.
     *
     * <p>The message is dispatched through the entity scheduler: on Folia the
     * autosell pass runs on the global region thread, which does not own the
     * player and must not talk to the connection directly.
     */
    private void notifyOwner(Spawner spawner, int items, double value) {
        if (plugin.getConfig().getBoolean("disable-autosell-notifications", false)) return;
        Player owner = Bukkit.getPlayer(spawner.owner());
        if (owner == null || !owner.isOnline()) return;

        String text = "&aAutosold &f" + items + " &aitem(s) for &f" + format(value) + "&a.";
        owner.getScheduler().execute(plugin, () -> owner.sendMessage(Text.of(text)), null, 1L);
    }

    // ------------------------------------------------------------- pricing

    /**
     * Reads the live config on every call: {@code reloadConfig()} swaps the object,
     * so a cached table would silently keep paying last boot's prices.
     */
    private PriceTable prices(Spawner spawner) {
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
        return new PriceTable(Map.copyOf(units), multiplier);
    }

    /**
     * Prices the priced part of storage and turns it into per-identity reservations.
     *
     * <p>Aggregating similar stacks here means the commit removes a quantity, not
     * a set of storage positions: positions shift whenever a producer appends and
     * a position-based quote would either miss loot or take the wrong stack.
     */
    private Priced price(List<ItemStack> contents, PriceTable table) {
        List<Reservation> reserved = new ArrayList<>();
        int items = 0;
        double total = 0.0;

        for (ItemStack stack : contents) {
            if (stack == null || stack.getType().isAir()) continue;
            Double unit = table.unitPrices().get(stack.getType().name());
            if (unit == null || unit <= 0.0) continue;
            int amount = stack.getAmount();
            if (amount <= 0) continue;

            Reservation matching = null;
            for (Reservation candidate : reserved) {
                if (candidate.template.isSimilar(stack)) {
                    matching = candidate;
                    break;
                }
            }
            if (matching == null) {
                reserved.add(new Reservation(stack.clone(), amount));
            } else {
                matching.remaining += amount;
            }
            items += amount;
            total += unit * amount;
        }
        return new Priced(List.copyOf(reserved), items, total * table.multiplier());
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
                int capacity = plugin.maximumItems(spawner);
                if (capacity > 0 && spawner.storedCount() < capacity) {
                    return Sale.refused(Status.NOTHING_TO_SELL, "storage must be full before collecting");
                }
            }
            Priced priced = price(spawner.contents(), table);
            if (!Double.isFinite(priced.value())) return Sale.refused(Status.FAILED, "sale price overflow");
            if (priced.items() <= 0 || priced.value() <= 0.0) {
                return Sale.refused(Status.NOTHING_TO_SELL, "nothing stored has a sell price");
            }

            SaleAttempt attempt = new SaleAttempt(spawner.id(), priced.reserved(), priced.items(), priced.value(), actor);
            if (!deposit(target, attempt.value)) {
                attempt.failBeforePayment();
                return new Sale(Status.FAILED, attempt.value, attempt.items, "Vault rejected the deposit");
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
     * Removes exactly the reserved amounts, taking stock by identity not by index.
     *
     * <p>Each pass snapshots live storage, plans which slots can satisfy which
     * reservation and then drains in descending slot order, because
     * {@link Spawner#drainSlot(int, int)} removes an emptied slot and would shift
     * every higher index. A slot that changed identity underneath the plan is put
     * back with {@link Spawner#storeVirtual(ItemStack, int)} and the pass is abandoned;
     * the attempt stays paid and its remaining count is retried later. Loot that
     * appeared after the quote is never in the reservation, so it stays put.
     */
    private boolean drain(SaleAttempt attempt, Spawner spawner) {
        for (int pass = 0; pass < MAX_DRAIN_PASSES; pass++) {
            if (attempt.outstanding() <= 0) return true;

            List<ItemStack> live = spawner.contents();
            List<DrainStep> steps = new ArrayList<>();

            for (Reservation reservation : attempt.reservations) {
                int need = reservation.remaining;
                if (need <= 0) continue;
                for (int index = 0; index < live.size() && need > 0; index++) {
                    ItemStack stack = live.get(index);
                    if (stack == null || stack.getType().isAir()) continue;
                    if (!reservation.template.isSimilar(stack)) continue;

                    int alreadyPlanned = 0;
                    for (DrainStep step : steps) {
                        if (step.index() == index) alreadyPlanned += step.amount();
                    }
                    int available = stack.getAmount() - alreadyPlanned;
                    if (available <= 0) continue;

                    int take = Math.min(need, available);
                    steps.add(new DrainStep(index, reservation, take));
                    need -= take;
                }
            }

            if (steps.isEmpty()) return false;
            steps.sort((a, b) -> Integer.compare(b.index(), a.index()));

            for (DrainStep step : steps) {
                ItemStack removed = spawner.drainSlot(step.index(), step.amount());
                if (removed == null || removed.getAmount() <= 0) return false;
                plugin.markDirty(spawner);
                if (!step.reservation().template.isSimilar(removed)) {
                    // A concurrent mutation moved a different stack into this slot;
                    // restore it untouched and re-plan from a fresh snapshot.
                    spawner.storeVirtual(removed, 0);
                    return false;
                }
                step.reservation().remaining -= removed.getAmount();
            }
        }
        return attempt.outstanding() <= 0;
    }

    /**
     * Pays through Vault and reports whether the provider confirmed the credit.
     *
     * <p>{@link dev.yanianz.bravenspawner.econ.Economy#deposit} reads
     * {@code EconomyResponse.transactionSuccess()}; a thrown provider exception or
     * a failed response both read as "not paid", which keeps the storage untouched
     * instead of deleting loot for money that never arrived.
     */
    private boolean deposit(OfflinePlayer target, double amount) {
        if (target == null) return false;
        if (plugin.economy() == null || !plugin.economy().enabled()) return false;
        try {
            return plugin.economy().deposit(target, amount);
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
