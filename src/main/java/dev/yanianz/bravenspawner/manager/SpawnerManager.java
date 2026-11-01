package dev.yanianz.bravenspawner.manager;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.boosters.Boosters;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.product.ProductRoller;
import dev.yanianz.bravenspawner.util.Schedulers;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns every spawner and the tick pass.
 *
 * <p>Each spawner owns exactly one lease: a chain of delayed region tasks, one per
 * production cycle. Every cycle samples a fresh interval and arms the next task
 * itself, so no repeating task is cancelled and reinstalled per cycle. The
 * lifecycle is idempotent: {@link #register(Spawner)} installs the lease,
 * {@link #startAll()} only fills gaps, and {@link #reschedule(Spawner)} replaces
 * the lease under a lock. A cycle continues its chain only while its lease is
 * still the live, current lease of a registered spawner, so a replaced or
 * unregistered chain stops and two chains can never run for the same block.
 *
 * <p>Every world read inside a cycle happens on the region scheduler that owns the
 * spawner block: the delayed task is bound to the block location and the
 * activation scan runs inside it. A failure in one spawner is logged, does not stop
 * the pass and does not break that spawner's chain.
 *
 * <p>Production is bounded independently of the virtual stack. Item yield is
 * aggregated in closed form, so a stack of 200 000 costs the same as a stack of
 * one. Mob and item products both generate virtual loot without spawning entities.
 */
public final class SpawnerManager {

    private final BravenSpawner plugin;
    private final Map<String, Spawner> byBlock = new ConcurrentHashMap<>();
    private final Map<UUID, Spawner> byId = new ConcurrentHashMap<>();
    /** Per-chunk index ({@code world:cx:cz}) so the chunk caps never scan every spawner. */
    private final Map<String, Set<Spawner>> byChunk = new ConcurrentHashMap<>();
    /** Per-world index so per-player passes never visit other worlds. */
    private final Map<String, Set<Spawner>> byWorld = new ConcurrentHashMap<>();
    private final Map<UUID, TaskLease> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGuiOpen = new ConcurrentHashMap<>();
    /** Spawners already reported as missing their block, so the log is not flooded. */
    private final Set<UUID> reportedMissing = ConcurrentHashMap.newKeySet();

    /**
     * Guards task installation. Cancelling and installing must be one step: a
     * caller that cancels, waits, then installs can race a second caller into two
     * live tasks for the same spawner, which is exactly the double-production bug
     * this class exists to prevent.
     */
    private final Object scheduleLock = new Object();

    private volatile Settings settings;

    public SpawnerManager(BravenSpawner plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public Settings settings() { return settings; }

    public void settings(Settings value) {
        this.settings = value;
        all().forEach(this::reschedule);
    }

    public Collection<Spawner> all() { return byId.values(); }
    public int size() { return byId.size(); }
    public Spawner byId(UUID id) { return id == null ? null : byId.get(id); }

    public static String key(String world, int x, int y, int z) {
        return world + ':' + x + ':' + y + ':' + z;
    }

    public Spawner at(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return byBlock.get(key(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    public List<Spawner> ownedBy(UUID owner) {
        List<Spawner> out = new ArrayList<>();
        if (owner == null) return out;
        for (Spawner s : byId.values()) if (s.isOwner(owner)) out.add(s);
        return out;
    }

    private static String chunkKey(String world, int chunkX, int chunkZ) {
        return world + ':' + chunkX + ':' + chunkZ;
    }

    private static String chunkKey(Spawner spawner) {
        return chunkKey(spawner.world(), spawner.x() >> 4, spawner.z() >> 4);
    }

    private Set<Spawner> chunk(World world, int chunkX, int chunkZ) {
        if (world == null) return Set.of();
        return byChunk.getOrDefault(chunkKey(world.getName(), chunkX, chunkZ), Set.of());
    }

    /**
     * Spawners whose chunk is within {@code radius} chunks (Chebyshev distance) of
     * {@code (chunkX, chunkZ)} in {@code world}.
     *
     * <p>Reads only the in-memory indexes, so any thread may call it. The cheaper
     * of two strategies is used: a lookup per chunk of the square, or a filtered
     * walk of the world's spawners when the world holds fewer spawners than the
     * square has chunks.
     */
    public List<Spawner> nearChunk(String world, int chunkX, int chunkZ, int radius) {
        List<Spawner> out = new ArrayList<>();
        if (world == null || radius < 0) return out;
        Set<Spawner> inWorld = byWorld.get(world);
        if (inWorld == null || inWorld.isEmpty()) return out;
        long side = 2L * radius + 1L;
        if (side * side < inWorld.size()) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Set<Spawner> set = byChunk.get(chunkKey(world, chunkX + dx, chunkZ + dz));
                    if (set != null) out.addAll(set);
                }
            }
            return out;
        }
        for (Spawner s : inWorld) {
            if (Math.abs((s.x() >> 4) - chunkX) <= radius && Math.abs((s.z() >> 4) - chunkZ) <= radius) out.add(s);
        }
        return out;
    }

    /** Sum of virtual stack units recorded in one chunk (per-chunk index, no full scan). */
    public int stackedInChunk(World world, int chunkX, int chunkZ) {
        long total = 0;
        for (Spawner s : chunk(world, chunkX, chunkZ)) total += s.stack();
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /** Number of managed spawner blocks in one chunk. */
    public int spawnersInChunk(World world, int chunkX, int chunkZ) {
        return chunk(world, chunkX, chunkZ).size();
    }

    /**
     * Whether a new spawner block carrying {@code units} stack units may be placed.
     * {@code spawner-limit-in-chunk} counts blocks by default; with
     * {@code spawner-limit-in-chunk-mode: stacks} it counts stack units.
     */
    public boolean chunkAllowsNewSpawner(World world, int chunkX, int chunkZ, int units) {
        Settings s = settings;
        if (s == null || s.spawnerLimitInChunk <= 0) return true;
        if (!s.chunkLimitCountsStacks) return spawnersInChunk(world, chunkX, chunkZ) + 1L <= s.spawnerLimitInChunk;
        return (long) stackedInChunk(world, chunkX, chunkZ) + Math.max(0, units) <= s.spawnerLimitInChunk;
    }

    /**
     * Stack units that may still be added to existing spawners in this chunk (merge,
     * buy-stack). Unlimited in the default block-count mode, where stacking does not
     * add blocks.
     */
    public int chunkUnitRoom(World world, int chunkX, int chunkZ) {
        Settings s = settings;
        if (s == null || s.spawnerLimitInChunk <= 0 || !s.chunkLimitCountsStacks) return Integer.MAX_VALUE;
        return Math.max(0, s.spawnerLimitInChunk - stackedInChunk(world, chunkX, chunkZ));
    }

    public boolean register(Spawner spawner) {
        if (spawner == null) return false;
        String key = key(spawner.world(), spawner.x(), spawner.y(), spawner.z());
        if (byBlock.putIfAbsent(key, spawner) != null) return false;
        byId.put(spawner.id(), spawner);
        byChunk.computeIfAbsent(chunkKey(spawner), k -> ConcurrentHashMap.newKeySet()).add(spawner);
        byWorld.computeIfAbsent(spawner.world(), k -> ConcurrentHashMap.newKeySet()).add(spawner);
        schedule(spawner);
        plugin.markDirty(spawner);
        return true;
    }

    public void unregister(Spawner spawner) {
        if (spawner == null) return;
        byBlock.remove(key(spawner.world(), spawner.x(), spawner.y(), spawner.z()), spawner);
        byChunk.computeIfPresent(chunkKey(spawner), (k, set) -> {
            set.remove(spawner);
            return set.isEmpty() ? null : set;
        });
        byWorld.computeIfPresent(spawner.world(), (k, set) -> {
            set.remove(spawner);
            return set.isEmpty() ? null : set;
        });
        if (byId.remove(spawner.id(), spawner)) plugin.markDeleted(spawner.id());
        reportedMissing.remove(spawner.id());
        synchronized (scheduleLock) {
            cancelLocked(spawner.id());
        }
    }

    /**
     * Fills in missing tasks without touching a spawner that is already running.
     *
     * <p>Boot registers every loaded spawner and then calls this; an unconditional
     * reschedule here would replace the handlers installed by {@code register} and
     * double the production rate for the whole session.
     */
    public void startAll() {
        for (Spawner spawner : byId.values()) {
            if (spawner == null) continue;
            synchronized (scheduleLock) {
                TaskLease existing = tasks.get(spawner.id());
                if (existing != null && existing.live()) continue;
                cancelLocked(spawner.id());
                install(spawner);
            }
        }
    }

    public void stopAll() {
        synchronized (scheduleLock) {
            for (UUID id : new ArrayList<>(tasks.keySet())) cancelLocked(id);
            tasks.clear();
        }
    }

    /** Replaces the task for one spawner; used after a tier change or a reload. */
    public void reschedule(Spawner spawner) {
        if (spawner == null) return;
        synchronized (scheduleLock) {
            cancelLocked(spawner.id());
            install(spawner);
        }
    }

    /** Test hook: live production tasks owned by one spawner id (0 or 1). */
    int liveTasks(UUID spawnerId) {
        TaskLease lease = spawnerId == null ? null : tasks.get(spawnerId);
        return lease != null && lease.live() ? 1 : 0;
    }

    /** Test hook: live production tasks across every registered spawner. */
    int liveTaskCount() {
        int live = 0;
        for (TaskLease lease : tasks.values()) if (lease.live()) live++;
        return live;
    }

    public boolean guiThrottled(UUID player, long cooldownMs) {
        if (player == null) return false;
        long now = System.currentTimeMillis();
        Long last = lastGuiOpen.get(player);
        if (last != null && now - last < cooldownMs) return true;
        lastGuiOpen.put(player, now);
        return false;
    }

    /** Installs the production lease, cancelling whatever the id held before. */
    private void schedule(Spawner spawner) {
        synchronized (scheduleLock) {
            cancelLocked(spawner.id());
            install(spawner);
        }
    }

    /** Caller must hold {@link #scheduleLock}. */
    private void install(Spawner spawner) {
        TaskLease lease = new TaskLease();
        tasks.put(spawner.id(), lease);
        if (!arm(spawner, lease, -1L)) {
            // Not schedulable now (world unloaded, no tiers): leave a gap that
            // startAll() or the next reschedule fills.
            tasks.remove(spawner.id(), lease);
            lease.cancel();
        }
    }

    /**
     * Schedules the next cycle of {@code lease}. Caller must hold {@link #scheduleLock}.
     *
     * @param precomputed the delay, computed outside the lock by the hot cycle path;
     *                    negative computes it here (rare install/reschedule paths)
     * @return false when no task could be scheduled
     */
    private boolean arm(Spawner spawner, TaskLease lease, long precomputed) {
        Location location = spawner.location();
        if (location == null) return false;
        Settings s = settings;
        if (s == null || s.tiers.isEmpty()) return false;
        long period = precomputed > 0 ? precomputed : nextPeriod(spawner, s);
        try {
            lease.handle(Schedulers.atLater(plugin, location, period, () -> cycle(spawner, location, lease)));
            return true;
        } catch (RuntimeException e) {
            // A world that unloads between lookup and install must not abort the
            // caller: the spawner simply stays unscheduled until the next pass.
            plugin.getLogger().warning("could not schedule " + spawner.id() + ": " + e.getMessage());
            return false;
        }
    }

    /** Production delay for the next cycle, re-sampled every cycle. */
    private long nextPeriod(Spawner spawner, Settings s) {
        var definition = plugin.product(spawner);
        long productDelay = definition == null ? 0L
                : ThreadLocalRandom.current().nextLong(definition.intervalTicks(), (long) definition.maxIntervalTicks() + 1);
        long period = s.productionPeriod(spawner.tier(), productDelay);
        double speed = plugin.boosters() == null ? 1.0
                : plugin.boosters().multiplier(Boosters.Type.SPEED, spawner.owner(), spawner.id());
        if (Double.isFinite(speed) && speed > 0.0) period = Math.max(20L, Math.round(period / speed));
        return Math.max(1L, period);
    }

    /** Caller must hold {@link #scheduleLock}. */
    private void cancelLocked(UUID id) {
        TaskLease lease = tasks.remove(id);
        if (lease != null) lease.cancel();
    }

    /**
     * One link of the chain; runs on the region that owns {@code location}.
     *
     * <p>A stale link (its lease was replaced or cancelled) neither produces nor
     * re-arms. A current link always re-arms, whether production ran, was skipped
     * or failed, so an unloaded chunk or a corrupted spawner pauses output without
     * ending the chain.
     */
    private void cycle(Spawner spawner, Location location, TaskLease lease) {
        if (!current(spawner, lease)) return;
        try {
            if (!spawner.corrupted()) produce(spawner, location);
        } finally {
            // Booster and island lookups run before the lock: region threads only
            // serialise on the brief lease check and handle swap.
            long period = -1L;
            Settings s = settings;
            try {
                if (s != null && !s.tiers.isEmpty() && current(spawner, lease)) period = nextPeriod(spawner, s);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("could not compute the next cycle of " + spawner.id() + ": " + e.getMessage());
            }
            synchronized (scheduleLock) {
                if (current(spawner, lease) && !arm(spawner, lease, period)) {
                    tasks.remove(spawner.id(), lease);
                    lease.cancel();
                }
            }
        }
    }

    private boolean current(Spawner spawner, TaskLease lease) {
        return lease.live() && tasks.get(spawner.id()) == lease && byId.get(spawner.id()) == spawner;
    }

    /** Runs on the region that owns the spawner block; all world access lives here. */
    private void produce(Spawner spawner, Location location) {
        try {
            Settings s = settings;
            if (s == null || !plugin.ready()) return;
            World world = location.getWorld();
            if (world == null) return;
            if (s.isBlacklisted(world.getName())) return;
            if (!world.isChunkLoaded(spawner.x() >> 4, spawner.z() >> 4)) return;
            if (!activated(world, location, s)) return;

            if (byId.get(spawner.id()) != spawner || spawner.corrupted()) return;
            if (!blockPresent(world, spawner, s)) return;
            if (spawner.kind() == Spawner.Kind.ITEM && !s.useItemSpawners) return;
            produceVirtual(spawner, s);
            if (plugin.particles() != null) plugin.particles().cycle(location);

            plugin.holograms().update(spawner);
        } catch (RuntimeException e) {
            // Logged and contained; cycle() re-arms the chain regardless.
            plugin.getLogger().warning("production failed for " + spawner.id() + ": " + e.getMessage());
        }
    }

    /**
     * Whether the managed block still exists. A block removed without a break event
     * (explosion with damage enabled, WorldEdit, {@code /setblock}) pauses output
     * instead of producing for an invisible record; stock and XP stay intact and
     * {@code restore-missing-spawners} can recreate the block on the next start.
     */
    private boolean blockPresent(World world, Spawner spawner, Settings s) {
        Material type = world.getBlockAt(spawner.x(), spawner.y(), spawner.z()).getType();
        if (type == Material.SPAWNER || type == s.spawnerMaterial) {
            reportedMissing.remove(spawner.id());
            return true;
        }
        if (reportedMissing.add(spawner.id())) {
            plugin.getLogger().warning("spawner " + spawner.id() + " at " + spawner.world() + " "
                    + spawner.x() + "," + spawner.y() + "," + spawner.z() + " has no block (found " + type
                    + "); production paused. Enable restore-missing-spawners or remove the record.");
        }
        return false;
    }

    /** Activation check; an empty nearby-player set means nobody is close enough. */
    private boolean activated(World world, Location location, Settings s) {
        if (!s.noActivationRange) return true;
        return !world.getNearbyPlayers(location, Math.max(1, s.activationRadius)).isEmpty();
    }

    /**
     * Item production in closed form.
     *
     * <p>The old pass rolled each drop once per stack unit, so a large stack turned
     * into a long loop. Deterministic drops multiply, probabilistic drops sample the
     * binomial for the whole stack at once, and the result is stored in one go: cost
     * is proportional to the number of configured drops, never to the stack size.
     */
    private void produceVirtual(Spawner spawner, Settings s) {
        var product = plugin.product(spawner);
        if (product == null) return;
        double multiplier = plugin.boosters().multiplier(Boosters.Type.LOOT,
                spawner.owner(), spawner.id());
        int credits = (int) Math.min(Integer.MAX_VALUE,
                Math.max(1.0, Math.floor(Math.max(1, spawner.stack()) * multiplier)));
        Random random = ThreadLocalRandom.current();
        for (var drop : product.drops()) {
            int amount = ProductRoller.amount(drop, credits, random);
            if (amount <= 0) continue;
            ItemStack output = new ItemStack(drop.material(), 1);
            if (drop.displayName() != null || drop.customModelData() >= 0) {
                var meta = output.getItemMeta();
                if (meta != null) {
                    if (drop.displayName() != null) meta.displayName(Text.item(drop.displayName()));
                    if (drop.customModelData() >= 0) meta.setCustomModelData(drop.customModelData());
                    output.setItemMeta(meta);
                }
            }
            // Over capacity the surplus is simply not produced (pause, not void).
            spawner.deposit(output, amount, plugin.stockLimits(spawner));
        }
        int xp = ProductRoller.range(
                product.xp(), product.maxXpPerCycle(), Math.max(1, spawner.stack()), random);
        double xpMultiplier = plugin.boosters().multiplier(Boosters.Type.XP,
                spawner.owner(), spawner.id());
        xp = (int) Math.min(Integer.MAX_VALUE, Math.round(xp * xpMultiplier));
        spawner.addXp(xp, plugin.maximumXp(spawner));
        spawner.produced(credits);
        plugin.markDirty(spawner);
    }

    /**
     * One spawner's chain: the handle of its pending delayed task plus a liveness
     * flag.
     *
     * <p>{@link #startAll()} uses liveness rather than presence to decide whether a
     * spawner is already scheduled. Handles are replaced and the lease is cancelled
     * only under {@link #scheduleLock}.
     */
    private static final class TaskLease {

        private final AtomicBoolean live = new AtomicBoolean(true);
        private volatile Schedulers.Cancelable handle;

        boolean live() { return live.get(); }

        void handle(Schedulers.Cancelable next) {
            this.handle = next;
            if (!live.get()) cancelQuietly(next);
        }

        void cancel() {
            if (!live.compareAndSet(true, false)) return;
            cancelQuietly(handle);
        }

        private static void cancelQuietly(Schedulers.Cancelable target) {
            if (target == null) return;
            try {
                target.cancel();
            } catch (RuntimeException ignored) {
                // the scheduler already dropped the task
            }
        }
    }
}
