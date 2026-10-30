package dev.yanianz.bravenspawner.manager;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Schedulers;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns every spawner and the tick pass.
 *
 * <p>Each spawner owns exactly one cancellable region task. The lifecycle is
 * idempotent: {@link #register(Spawner)} installs the task, {@link #startAll()}
 * only fills gaps, and {@link #reschedule(Spawner)} replaces the task under a
 * lock so two production cycles can never run for the same block.
 *
 * <p>Every world read inside a cycle happens on the region scheduler that owns the
 * spawner block: the tick callback re-dispatches before it touches the world, and
 * the activation scan stays inside that dispatch. A failure in one
 * spawner is logged and does not stop the pass.
 *
 * <p>Production is bounded independently of the virtual stack. Item yield is
 * aggregated in closed form, so a stack of 200 000 costs the same as a stack of
 * one. Mob and item products both generate virtual loot without spawning entities.
 */
public final class SpawnerManager {

    /**
     * Trials below this mean use exact geometric sampling; above it the binomial
     * is approximated by a normal with mean {@code np} and variance {@code np(1-p)}.
     */
    private static final double EXACT_TRIAL_THRESHOLD = 64.0;

    private final BravenSpawner plugin;
    private final Map<String, Spawner> byBlock = new ConcurrentHashMap<>();
    private final Map<UUID, Spawner> byId = new ConcurrentHashMap<>();
    private final Map<UUID, TaskLease> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGuiOpen = new ConcurrentHashMap<>();

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

    /**
     * Sum of virtual stack units recorded in one chunk.
     *
     * <p>Used by the placement and merge caps. The index is a full map scan; it is
     * only reached from a player action, never from the tick pass.
     */
    public int stackedInChunk(World world, int chunkX, int chunkZ) {
        if (world == null) return 0;
        String name = world.getName();
        long total = 0;
        for (Spawner s : byId.values()) {
            if (s == null || !s.world().equals(name)) continue;
            if ((s.x() >> 4) != chunkX || (s.z() >> 4) != chunkZ) continue;
            total += s.stack();
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public boolean register(Spawner spawner) {
        if (spawner == null) return false;
        String key = key(spawner.world(), spawner.x(), spawner.y(), spawner.z());
        if (byBlock.putIfAbsent(key, spawner) != null) return false;
        byId.put(spawner.id(), spawner);
        schedule(spawner);
        plugin.markDirty(spawner);
        return true;
    }

    public void unregister(Spawner spawner) {
        if (spawner == null) return;
        byBlock.remove(key(spawner.world(), spawner.x(), spawner.y(), spawner.z()), spawner);
        if (byId.remove(spawner.id(), spawner)) plugin.markDeleted(spawner.id());
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

    /** Installs the production task, cancelling whatever the id held before. */
    private void schedule(Spawner spawner) {
        synchronized (scheduleLock) {
            cancelLocked(spawner.id());
            install(spawner);
        }
    }

    /** Caller must hold {@link #scheduleLock}. */
    private void install(Spawner spawner) {
        Location location = spawner.location();
        if (location == null) return;
        Settings s = settings;
        if (s == null || s.tiers.isEmpty()) return;
        Settings.Tier tier = s.tiers.get(Math.min(Math.max(0, spawner.tier()), s.tiers.size() - 1));
        var definition = plugin.product(spawner);
        long period = Math.max(20L, tier.intervalTicks);
        if (definition != null && spawner.tier() == 0) {
            period = ThreadLocalRandom.current().nextLong(definition.intervalTicks(), (long) definition.maxIntervalTicks() + 1);
        }
        double speed = plugin.boosters() == null ? 1.0
                : plugin.boosters().multiplier(dev.yanianz.bravenspawner.boosters.Boosters.Type.SPEED, spawner.owner(), spawner.id());
        if (Double.isFinite(speed) && speed > 0.0) period = Math.max(20L, Math.round(period / speed));
        try {
            Schedulers.Cancelable handle = Schedulers.repeating(plugin, location, period, () -> tick(spawner));
            tasks.put(spawner.id(), new TaskLease(handle));
        } catch (RuntimeException e) {
            // A world that unloads between lookup and install must not abort the
            // caller: the spawner simply stays unscheduled until the next pass.
            plugin.getLogger().warning("could not schedule " + spawner.id() + ": " + e.getMessage());
        }
    }

    /** Caller must hold {@link #scheduleLock}. */
    private void cancelLocked(UUID id) {
        TaskLease lease = tasks.remove(id);
        if (lease != null) lease.cancel();
    }

    /**
     * Region callback entry point.
     *
     * <p>The repeating task is registered against the spawner's location, but the
     * actual production pass is dispatched again on purpose: this method is also
     * reachable from diagnostics and tests, and a single dispatch point guarantees
     * no world method is ever called from a foreign region thread.
     */
    private void tick(Spawner spawner) {
        if (spawner == null || spawner.corrupted()) return;
        Location location = spawner.location();
        if (location == null) return;
        try {
            Schedulers.at(plugin, location, () -> produce(spawner, location));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("could not dispatch production for "
                    + spawner.id() + ": " + e.getMessage());
        }
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
            if (spawner.kind() == Spawner.Kind.ITEM && !s.useItemSpawners) return;
            produceVirtual(spawner, s);

            plugin.holograms().update(spawner);
            var definition = plugin.product(spawner);
            if (definition != null) reschedule(spawner);
        } catch (RuntimeException e) {
            // A thrown exception would cancel the repeating task on Folia, which
            // would silently stop the spawner for the rest of the session.
            plugin.getLogger().warning("production failed for " + spawner.id() + ": " + e.getMessage());
        }
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
        double multiplier = plugin.boosters().multiplier(dev.yanianz.bravenspawner.boosters.Boosters.Type.LOOT,
                spawner.owner(), spawner.id());
        int credits = (int) Math.min(Integer.MAX_VALUE,
                Math.max(1.0, Math.floor(Math.max(1, spawner.stack()) * multiplier)));
        Random random = ThreadLocalRandom.current();
        for (var drop : product.drops()) {
            int amount = dev.yanianz.bravenspawner.product.ProductRoller.amount(drop, credits, random);
            if (amount <= 0) continue;
            ItemStack output = new ItemStack(drop.material(), amount);
            if (drop.displayName() != null || drop.customModelData() >= 0) {
                var meta = output.getItemMeta();
                if (meta != null) {
                    if (drop.displayName() != null) meta.displayName(dev.yanianz.bravenspawner.util.Text.of(drop.displayName()));
                    if (drop.customModelData() >= 0) meta.setCustomModelData(drop.customModelData());
                    output.setItemMeta(meta);
                }
            }
            spawner.storeVirtual(output, plugin.maximumItems(spawner));
        }
        int xp = dev.yanianz.bravenspawner.product.ProductRoller.range(
                product.xp(), product.maxXpPerCycle(), Math.max(1, spawner.stack()), random);
        double xpMultiplier = plugin.boosters().multiplier(dev.yanianz.bravenspawner.boosters.Boosters.Type.XP,
                spawner.owner(), spawner.id());
        xp = (int) Math.min(Integer.MAX_VALUE, Math.round(xp * xpMultiplier));
        spawner.addXp(xp, plugin.maximumXp(spawner));
        spawner.produced(credits);
        plugin.markDirty(spawner);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private int storageCapacity(Settings s, Spawner spawner) {
        if (s.unlimitedStorage || s.tiers.isEmpty()) return 0;
        return s.tiers.get(Math.min(Math.max(0, spawner.tier()), s.tiers.size() - 1)).capacity;
    }

    /**
     * Rolls one drop rule for {@code units} independent production cycles.
     *
     * <p>Expected value matches the per-unit roll exactly. Deterministic drops
     * multiply; probabilistic drops use the binomial distribution for the whole
     * batch. Below {@value #EXACT_TRIAL_THRESHOLD} expected successes the sampler is
     * exact (geometric gaps, cost proportional to successes); above it the normal
     * approximation {@code N(np, np(1-p))} is used and clamped to {@code [0, trials]},
     * which keeps the relative error near {@code 1/sqrt(np)} — under 12.5% in the
     * worst case at the threshold and shrinking as the batch grows.
     */
    static int rollAggregate(Settings.Drop drop, int units, Random random) {
        if (drop == null || units <= 0) return 0;
        if (drop.chance <= 0.0) return 0;
        int perUnit = Math.max(1, drop.amount);
        if (drop.chance >= 1.0) return saturatingMultiply(perUnit, units);
        long trials = (long) perUnit * units;
        if (trials <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, sampleBinomial(trials, drop.chance, random));
    }

    private static long sampleBinomial(long trials, double probability, Random random) {
        double mean = trials * probability;
        if (mean < EXACT_TRIAL_THRESHOLD) {
            double logFailure = Math.log1p(-probability);
            long successes = 0;
            long position = -1;
            while (true) {
                double u = random.nextDouble();
                double gap = Math.floor(Math.log(1.0 - u) / logFailure);
                position += 1 + (gap < 0 ? 0 : (long) gap);
                if (position >= trials) break;
                successes++;
            }
            return successes;
        }

        double deviation = Math.sqrt(trials * probability * (1.0 - probability));
        long sample = Math.round(mean + deviation * random.nextGaussian());
        if (sample < 0) return 0;
        return Math.min(sample, trials);
    }

    private static int saturatingMultiply(int left, int right) {
        long product = (long) left * right;
        return product > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) product;
    }

    /**
     * One task handle plus its liveness flag.
     *
     * <p>The flag matters for the lifecycle: a cancelled handle stays in the map
     * only long enough for the next install to replace it, and {@link #startAll()}
     * uses liveness rather than presence to decide whether a spawner is already
     * scheduled.
     */
    private static final class TaskLease {

        private final Schedulers.Cancelable handle;
        private final AtomicBoolean live = new AtomicBoolean(true);

        TaskLease(Schedulers.Cancelable handle) {
            this.handle = handle;
        }

        boolean live() { return live.get(); }

        void cancel() {
            if (!live.compareAndSet(true, false)) return;
            try {
                handle.cancel();
            } catch (RuntimeException ignored) {
                // the scheduler already dropped the task
            }
        }
    }
}
