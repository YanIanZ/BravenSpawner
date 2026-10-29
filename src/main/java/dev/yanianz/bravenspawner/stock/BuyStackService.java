package dev.yanianz.bravenspawner.stock;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.audit.AuditEvent;
import dev.yanianz.bravenspawner.audit.AuditLog;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Economics behind the {@code buy_stack} menu: how many stacks a player may buy,
 * what it costs and what happens when the money or the provider is missing.
 *
 * <p>Buying mutates two systems that cannot be rolled back together — Vault and the
 * in-memory spawner — so the order is chosen to fail closed: every local check runs
 * before the charge, the charge runs before the stack is added, and a stack that
 * cannot be applied is refunded. A provider that throws is treated as a failed
 * charge, never as a success: crediting the stack anyway would mint value.
 *
 * <p>The service reads limits from an optional {@code gui/buy_stack.yml} (the same
 * file the menu engine renders) and falls back to config keys for servers that never
 * installed the menu file. The YAML wins when both exist because it ships beside the
 * menu it describes: an admin editing the menu sees the same numbers the till uses.
 *
 * <p>Not thread-safe by design in one respect: {@link #buy} must run on the thread
 * that owns the spawner (region thread on Folia, main thread on Paper). Vault calls
 * and the stack mutation must observe the same order; {@link #maxBuyable} is a plain
 * read and may be called from the menu thread.
 */
public final class BuyStackService {

    /** Used when neither buy_stack.yml nor config supplies a per-click cap. */
    public static final int DEFAULT_MAX_PER_PURCHASE = 64;

    /** Used when neither buy_stack.yml nor config supplies a cooldown. */
    public static final long DEFAULT_COOLDOWN_MILLIS = 500L;

    /** Cap on remembered purchase times; beyond this the map is pruned or cleared. */
    private static final int MAX_TRACKED_PLAYERS = 4_096;

    /** Minimum retention window for a player's last-purchase timestamp. */
    private static final long MIN_RETENTION_MILLIS = 60_000L;

    /** Outcome of a purchase attempt; only {@link Status#OK} moved money and stock. */
    public enum Status {
        OK,
        /** Price is not positive or the purchase feature is not configured. */
        DISABLED,
        /** No Vault provider is registered, so a charge cannot be confirmed. */
        NO_ECONOMY,
        /** Null player/spawner or a non-positive stack count. */
        INVALID,
        /** The per-player cooldown or backoff has not elapsed. */
        COOLDOWN,
        /** The ceiling is zero: full, capped, unaffordable or no economy head-room. */
        LIMIT,
        /** The spawner has no room left under {@code stack-limit}. */
        NO_ROOM,
        /** Adding the stacks would exceed {@code spawner-limit-in-chunk}. */
        CHUNK_LIMIT,
        /** Vault refused or failed the withdrawal; nothing was added. */
        CHARGE_FAILED,
        /** Money left the account but the stack could not be applied; refund attempted. */
        APPLY_FAILED
    }

    /**
     * Result of one purchase.
     *
     * @param status                 what happened
     * @param requested              stacks the caller asked for
     * @param bought                 stacks actually added (0 unless {@code OK})
     * @param charged                money actually taken (0 unless {@code OK})
     * @param stackAfter             spawner stack after the call
     * @param cooldownRemainingMillis wait left on the per-player gate, or 0
     */
    public record Result(Status status, int requested, int bought, double charged,
                         int stackAfter, long cooldownRemainingMillis) {

        public boolean ok() {
            return status == Status.OK;
        }

        static Result refused(Status status, int requested, int stackAfter, long cooldown) {
            return new Result(status, Math.max(0, requested), 0, 0.0,
                    Math.max(0, stackAfter), Math.max(0L, cooldown));
        }
    }

    /**
     * Immutable price/limit snapshot.
     *
     * @param pricePerStack  money charged for one stack; non-positive disables buying
     * @param maxPerPurchase cap for one click; 0 or less means unlimited
     * @param maxPerPlayer   cap on the stacks a buyer may own; 0 or less means unlimited
     * @param cooldownMillis fixed gate between purchases from {@code cooldowns.buy}
     * @param backoffMillis  economy-side floor from buy_stack limits; the larger of
     *                       the two delays is enforced
     */
    public record Limits(double pricePerStack, int maxPerPurchase, int maxPerPlayer,
                         long cooldownMillis, long backoffMillis) {

        public long effectiveCooldownMillis() {
            return Math.max(Math.max(0L, cooldownMillis), Math.max(0L, backoffMillis));
        }
    }

    private final BravenSpawner plugin;
    private final Map<UUID, Long> lastPurchase = new ConcurrentHashMap<>();
    private volatile Limits limits;
    private volatile AuditLog audit;

    public BuyStackService(BravenSpawner plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Optional audit sink; harmless when the logger is disabled. */
    public BuyStackService audit(AuditLog auditLog) {
        this.audit = auditLog;
        return this;
    }

    /**
     * Re-reads config and the optional {@code gui/buy_stack.yml}.
     *
     * <p>Cooldown state survives a reload: an admin changing prices while players
     * are clicking must not hand everyone a fresh free purchase.
     */
    public void reload() {
        double price = plugin.getConfig().getDouble("buy-stack.price-per-stack",
                plugin.getConfig().getDouble("economy.purchase-price", 0.0));
        int perPurchase = plugin.getConfig().getInt("buy-stack.max-per-purchase",
                DEFAULT_MAX_PER_PURCHASE);
        int perPlayer = plugin.getConfig().getInt("buy-stack.max-per-player", 0);
        long cooldown = plugin.getConfig().getLong("cooldowns.buy", DEFAULT_COOLDOWN_MILLIS);
        long backoffSeconds = plugin.getConfig().getLong("buy-stack.backoff-seconds", 0L);

        File menuFile = buyStackFile();
        YamlConfiguration menuYaml = null;
        if (menuFile != null) {
            menuYaml = YamlConfiguration.loadConfiguration(menuFile);
        } else {
            // The menu file is optional in the data folder; reading the packaged copy
            // lets a future build ship default limits without every server copying the
            // file first.
            var resource = plugin.getResource("gui/buy_stack.yml");
            if (resource != null) {
                try (var reader = new java.io.InputStreamReader(resource, java.nio.charset.StandardCharsets.UTF_8)) {
                    menuYaml = YamlConfiguration.loadConfiguration(reader);
                } catch (java.io.IOException ignored) {
                    // A broken optional file falls back to config; no event is lost.
                }
            }
        }
        if (menuYaml != null) {
            ConfigurationSection limits = menuYaml.getConfigurationSection("limits");
            if (limits != null) {
                price = limits.getDouble("price-per-stack", price);
                perPurchase = limits.getInt("per-purchase", perPurchase);
                perPlayer = limits.getInt("per-player", perPlayer);
                backoffSeconds = limits.getLong("backoff-seconds", backoffSeconds);
            }
        }

        this.limits = new Limits(price, perPurchase, perPlayer,
                Math.max(0L, cooldown), Math.max(0L, backoffSeconds) * 1_000L);
    }

    /** True when a positive price and a live Vault provider make buying possible. */
    public boolean enabled() {
        return limits.pricePerStack() > 0.0 && economyReady();
    }

    /** Price of one stack, for the menu's {@code %price%}. */
    public double pricePerStack() {
        return limits.pricePerStack();
    }

    /** Per-click cap, for diagnostics; 0 means unlimited. */
    public int maxPerPurchase() {
        return limits.maxPerPurchase();
    }

    /** Per-player ownership cap, for diagnostics; 0 means unlimited. */
    public int maxPerPlayer() {
        return limits.maxPerPlayer();
    }

    /** Total price for {@code stacks}; the menu's {@code %total_price%}. */
    public double totalPrice(int stacks) {
        if (stacks <= 0) return 0.0;
        return limits.pricePerStack() * stacks;
    }

    /** Money text using the shared {@code money-decimals} rules. */
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

    /**
     * The most stacks {@code player} could buy for a spawner currently holding
     * {@code slotCount} stacks.
     *
     * <p>Every limiter is applied and the smallest wins: per-click cap, the spawner's
     * room under {@code stack-limit}, the buyer's per-player ownership cap, and what
     * the balance can actually pay for. The cooldown is intentionally excluded — the
     * menu should keep showing an honest ceiling while a click is briefly gated,
     * otherwise the number would flicker to zero between pages.
     *
     * @return headroom, or 0 when buying is disabled, the player is offline or the
     *         spawner is full
     */
    public int maxBuyable(UUID player, int slotCount) {
        Limits snapshot = limits;
        if (player == null || !economyReady()) return 0;
        if (snapshot.pricePerStack() <= 0.0) return 0;

        int room = roomFor(slotCount);
        if (room <= 0) return 0;

        int cap = snapshot.maxPerPurchase() <= 0
                ? room : Math.min(room, snapshot.maxPerPurchase());

        if (snapshot.maxPerPlayer() > 0) {
            int owned = ownedStacks(player);
            cap = Math.min(cap, Math.max(0, snapshot.maxPerPlayer() - owned));
        }

        Player online = Bukkit.getPlayer(player);
        if (online == null) return 0;
        double balance;
        try {
            balance = plugin.economy().balance(online);
        } catch (RuntimeException e) {
            // A provider throwing during a balance read must not look like wealth.
            return 0;
        }
        int affordable = (int) Math.floor(balance / snapshot.pricePerStack() + 1e-9);
        cap = Math.min(cap, Math.max(0, affordable));
        return Math.max(0, cap);
    }

    /** Milliseconds until {@code player} may buy again; 0 when the gate is open. */
    public long cooldownRemaining(UUID player) {
        if (player == null) return 0L;
        long wait = limits.effectiveCooldownMillis();
        if (wait <= 0L) return 0L;
        Long last = lastPurchase.get(player);
        if (last == null) return 0L;
        return Math.max(0L, wait - (System.currentTimeMillis() - last));
    }

    /**
     * Charges {@code player} and adds {@code stacks} to {@code spawner}.
     *
     * <p>A request larger than the current ceiling is trimmed to what
     * {@link #maxBuyable} reports and only the trimmed amount is charged; that is what
     * lets the menu's "buy max" pass {@link Integer#MAX_VALUE} without the caller
     * having to race a second read of the limits. The original request is preserved in
     * {@link Result#requested} so the caller can tell the player what was trimmed.
     *
     * <p>If the charge succeeds but the stack cannot be applied (a concurrent merge
     * filled the spawner between the check and the write), the money is refunded and
     * the result is {@link Status#APPLY_FAILED}. A refund that the provider rejects
     * is logged at severe: at that point the trail no longer balances, and hiding it
     * would be worse than a noisy console.
     */
    public Result buy(Player player, Spawner spawner, int stacks) {
        if (player == null || spawner == null || stacks <= 0) {
            return Result.refused(Status.INVALID, stacks, spawner == null ? 0 : spawner.stack(), 0L);
        }
        Limits snapshot = limits;
        if (!economyReady()) {
            return Result.refused(Status.NO_ECONOMY, stacks, spawner.stack(), 0L);
        }
        if (snapshot.pricePerStack() <= 0.0) {
            return Result.refused(Status.DISABLED, stacks, spawner.stack(), 0L);
        }

        int limit = maxBuyable(player.getUniqueId(), spawner.stack());
        if (limit <= 0) {
            return Result.refused(Status.LIMIT, stacks, spawner.stack(), 0L);
        }
        int amount = Math.min(stacks, limit);

        long remaining = cooldownRemaining(player.getUniqueId());
        if (remaining > 0L) {
            return Result.refused(Status.COOLDOWN, stacks, spawner.stack(), remaining);
        }

        Settings settings = plugin.settings();
        int stackLimit = settings == null ? 1 : Math.max(1, settings.stackLimit);

        if (!chunkAllows(spawner, amount, settings)) {
            return Result.refused(Status.CHUNK_LIMIT, stacks, spawner.stack(), 0L);
        }
        if (spawner.roomFor(stackLimit) < amount) {
            return Result.refused(Status.NO_ROOM, stacks, spawner.stack(), 0L);
        }

        double total = totalPrice(amount);
        if (!Double.isFinite(total) || total <= 0.0) {
            return Result.refused(Status.INVALID, stacks, spawner.stack(), 0L);
        }

        boolean paid;
        try {
            paid = plugin.economy().withdraw(player, total);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("buy-stack withdrawal failed for "
                    + player.getUniqueId() + ": " + e.getMessage());
            paid = false;
        }
        if (!paid) {
            return Result.refused(Status.CHARGE_FAILED, stacks, spawner.stack(), 0L);
        }

        if (!spawner.tryAddStack(amount, stackLimit)) {
            boolean refunded = refund(player, total);
            if (!refunded) {
                plugin.getLogger().severe("buy-stack could not add " + amount
                        + " stack(s) to spawner " + spawner.id() + " and the refund of "
                        + total + " to " + player.getUniqueId() + " was rejected");
            }
            return Result.refused(Status.APPLY_FAILED, stacks, spawner.stack(), 0L);
        }

        long now = System.currentTimeMillis();
        lastPurchase.put(player.getUniqueId(), now);
        prune(now, snapshot.effectiveCooldownMillis());
        persist(spawner);
        recordAudit(player, spawner, amount, total);
        return new Result(Status.OK, stacks, amount, total, spawner.stack(), 0L);
    }

    private boolean economyReady() {
        return plugin.economy() != null && plugin.economy().enabled();
    }

    private int roomFor(int slotCount) {
        Settings settings = plugin.settings();
        int stackLimit = settings == null ? 1 : Math.max(1, settings.stackLimit);
        return Math.max(0, stackLimit - Math.max(0, slotCount));
    }

    /**
     * Total stack units across every spawner the player owns.
     *
     * <p>Derived from the live manager instead of a purchase ledger: the cap then
     * survives restarts, can never drift from what the player actually holds, and
     * needs no per-player storage of its own.
     */
    private int ownedStacks(UUID player) {
        if (plugin.manager() == null) return 0;
        long total = 0L;
        for (Spawner spawner : plugin.manager().ownedBy(player)) {
            if (spawner != null) total += spawner.stack();
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /**
     * Mirrors the merge-path chunk cap: buying adds virtual stack units to a chunk,
     * so the same limit must apply or the menu would be a bypass for it.
     */
    private boolean chunkAllows(Spawner spawner, int stacks, Settings settings) {
        if (settings == null || settings.spawnerLimitInChunk <= 0) return true;
        if (plugin.manager() == null) return true;
        World world = Bukkit.getWorld(spawner.world());
        if (world == null) return true;
        long total = plugin.manager().stackedInChunk(world,
                spawner.x() >> 4, spawner.z() >> 4);
        return total + stacks <= settings.spawnerLimitInChunk;
    }

    private boolean refund(Player player, double amount) {
        try {
            return plugin.economy().deposit(player, amount);
        } catch (RuntimeException e) {
            plugin.getLogger().severe("buy-stack refund threw for "
                    + player.getUniqueId() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Persists the new stack off the owning thread.
     *
     * <p>The region thread may not block on SQL, and the checkpoint service only
     * writes records it knows are dirty, so a buy schedules its own upsert. A failure
     * is logged and leaves the next checkpoint to retry from the live object.
     */
    private void persist(Spawner spawner) {
        if (plugin.storage() == null || !plugin.isEnabled()) return;
        try {
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                try {
                    plugin.storage().upsert(spawner);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("buy-stack persistence failed for spawner "
                            + spawner.id() + ": " + e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            plugin.getLogger().warning("buy-stack persistence could not be scheduled for spawner "
                    + spawner.id() + ": " + e.getMessage());
        }
    }

    private void recordAudit(Player player, Spawner spawner, int stacks, double total) {
        AuditLog sink = audit;
        if (sink == null) return;
        sink.record(AuditEvent.of(spawner, AuditEvent.Action.STACK, player.getUniqueId(),
                player.getName(), stacks, "buy_stack purchase for " + format(total)));
    }

    /**
     * Keeps the last-purchase map bounded on servers that see many transient UUIDs.
     * Entries older than the gate cannot affect a purchase, so dropping them changes
     * no decision.
     */
    private void prune(long now, long gateMillis) {
        if (lastPurchase.size() <= MAX_TRACKED_PLAYERS) return;
        long retention = Math.max(MIN_RETENTION_MILLIS, gateMillis * 4L);
        lastPurchase.entrySet().removeIf(entry -> now - entry.getValue() > retention);
        if (lastPurchase.size() > MAX_TRACKED_PLAYERS) lastPurchase.clear();
    }

    /** Optional menu file, preferring the path the menu engine uses. */
    private File buyStackFile() {
        File gui = new File(plugin.getDataFolder(), "gui/buy_stack.yml");
        if (gui.isFile()) return gui;
        File root = new File(plugin.getDataFolder(), "buy_stack.yml");
        return root.isFile() ? root : null;
    }
}
