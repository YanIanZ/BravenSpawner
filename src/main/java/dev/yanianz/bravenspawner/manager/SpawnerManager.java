package dev.yanianz.bravenspawner.manager;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Schedulers;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * the entity spawn and proximity scan stay inside that dispatch. A failure in one
 * spawner is logged and does not stop the pass.
 *
 * <p>Production is bounded independently of the virtual stack. Item yield is
 * aggregated in closed form, so a stack of 200 000 costs the same as a stack of
 * one. A mob spawner performs at most {@value #MAX_PHYSICAL_BATCH} real spawns per
 * cycle and carries the remainder as a capped backlog.
 */
public final class SpawnerManager {

    /** Real entity spawns one spawner may perform in a single tick pass. */
    private static final int MAX_PHYSICAL_BATCH = 16;

    /** Hard ceiling on carried mob credits so a long pause cannot bank work forever. */
    private static final int MAX_PENDING_CREDITS = 512;

    /** Live mobs tolerated for each unit of stack before physical production pauses. */
    private static final int MOBS_PER_STACK = 8;

    /** Radius used for the per-spawner mob census and the activation check. */
    private static final int PROXIMITY_RADIUS = 16;

    /** Attempts spent looking for a passable block inside the spawner chunk. */
    private static final int SPAWN_ATTEMPTS = 8;

    /** Horizontal scatter radius, clamped to the spawner chunk for region safety. */
    private static final int SCATTER_RADIUS = 4;

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
        return true;
    }

    public void unregister(Spawner spawner) {
        if (spawner == null) return;
        byBlock.remove(key(spawner.world(), spawner.x(), spawner.y(), spawner.z()), spawner);
        byId.remove(spawner.id(), spawner);
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
        long period = Math.max(20L, tier.intervalTicks);
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
            if (s == null) return;
            World world = location.getWorld();
            if (world == null) return;
            if (s.isBlacklisted(world.getName())) return;
            if (!world.isChunkLoaded(spawner.x() >> 4, spawner.z() >> 4)) return;
            if (!activated(world, location, s)) return;

            if (spawner.kind() == Spawner.Kind.ITEM) produceItem(spawner, s);
            else produceMob(spawner, s, location, world);

            plugin.holograms().update(spawner);
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
    private void produceItem(Spawner spawner, Settings s) {
        Settings.Product product = s.items.get(spawner.product());
        if (product == null) return;

        int credits = Math.max(1, spawner.stack());
        int capacity = storageCapacity(s, spawner);
        Random random = ThreadLocalRandom.current();

        for (Settings.Drop drop : product.drops) {
            if (drop == null || drop.material == null) continue;
            int amount = rollAggregate(drop, credits, random);
            if (amount <= 0) continue;
            spawner.store(new ItemStack(drop.material, amount), capacity);
        }
        if (product.xp > 0) spawner.addXp(saturatingMultiply(product.xp, credits));
        spawner.produced(credits);
    }

    /**
     * Physical mob production with a per-cycle budget.
     *
     * <p>At most {@value #MAX_PHYSICAL_BATCH} entities are spawned in one pass and
     * the census that decides the cap runs once, not once per stack unit. Credits
     * that could not be spent are carried on the model, bounded by
     * {@value #MAX_PENDING_CREDITS} so an offline spawner cannot bank unbounded
     * work and then dump it in one tick.
     */
    private void produceMob(Spawner spawner, Settings s, Location base, World world) {
        Settings.Product product = s.mobs.get(spawner.product());
        if (product == null) return;

        EntityType type;
        try {
            type = EntityType.valueOf(product.id);
        } catch (IllegalArgumentException | NullPointerException e) {
            return;
        }
        if (type == null || !type.isSpawnable()) return;

        long due = (long) Math.max(1, spawner.stack()) + spawner.pending();
        int requested = (int) Math.min(MAX_PENDING_CREDITS, due);

        long cap = Math.max(1L, (long) spawner.stack() * MOBS_PER_STACK);
        int alive = countSpawned(world, base, spawner.id());
        long room = cap - alive;

        int batch = (int) Math.min(Math.min(requested, MAX_PHYSICAL_BATCH), Math.max(0L, room));
        int spawned = 0;
        for (int i = 0; i < batch; i++) {
            if (!spawnOne(spawner, base, world, type)) break;
            spawned++;
        }

        spawner.pending((int) Math.min(MAX_PENDING_CREDITS, Math.max(0, requested - spawned)));
        if (spawned > 0) spawner.produced(spawned);
    }

    /**
     * Counts the entities this spawner already owns.
     *
     * <p>This is the read that cannot be moved: it must observe the live region, so
     * it happens inside the production pass that runs on the owning scheduler and
     * never from the global or async thread.
     */
    private int countSpawned(World world, Location base, UUID spawnerId) {
        String id = spawnerId.toString();
        int count = 0;
        for (Entity entity : world.getNearbyEntities(base,
                PROXIMITY_RADIUS, PROXIMITY_RADIUS, PROXIMITY_RADIUS)) {
            String tag = entity.getPersistentDataContainer()
                    .get(plugin.spawnerIdKey(), PersistentDataType.STRING);
            if (id.equals(tag)) count++;
        }
        return count;
    }

    /**
     * Spawns one entity on the region that owns the spawner.
     *
     * <p>Candidates are clamped to the spawner's own chunk, so the highest-block
     * lookup, the passability test and the spawn itself all touch state owned by
     * the current region. A Folia region never spans two chunks by accident, so a
     * scatter step cannot land work on a foreign thread.
     */
    private boolean spawnOne(Spawner spawner, Location base, World world, EntityType type) {
        Random random = ThreadLocalRandom.current();
        int chunkMinX = (spawner.x() >> 4) << 4;
        int chunkMinZ = (spawner.z() >> 4) << 4;
        int chunkMaxX = chunkMinX + 15;
        int chunkMaxZ = chunkMinZ + 15;

        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            int x = clamp(spawner.x() + random.nextInt(-SCATTER_RADIUS, SCATTER_RADIUS + 1),
                    chunkMinX, chunkMaxX);
            int z = clamp(spawner.z() + random.nextInt(-SCATTER_RADIUS, SCATTER_RADIUS + 1),
                    chunkMinZ, chunkMaxZ);
            Location candidate = new Location(world, x + 0.5, 0.0, z + 0.5);
            candidate.setY(world.getHighestBlockYAt(x, z) + 1.0);
            if (!candidate.getBlock().isPassable()) continue;

            try {
                Entity entity = world.spawnEntity(candidate, type);
                entity.getPersistentDataContainer().set(
                        plugin.spawnerIdKey(), PersistentDataType.STRING, spawner.id().toString());
                if (entity instanceof LivingEntity living) {
                    living.setRemoveWhenFarAway(false);
                    living.setPersistent(true);
                }
                return true;
            } catch (RuntimeException e) {
                plugin.getLogger().warning("spawn failed at " + candidate + ": " + e.getMessage());
                return false;
            }
        }
        return false;
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
