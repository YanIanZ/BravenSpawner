package dev.yanianz.bravenspawner.mechanics;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.model.StockEntry;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Hopper;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Emulates a hopper pulling from a virtual spawner.
 *
 * <p>The spawner block is decorative; there is no real container inventory for a
 * vanilla hopper to read. A periodic pass finds hoppers one or two blocks below a
 * spawner and moves a single stack into the hopper. Items are removed with
 * {@link Spawner#drainSlotIfSimilar(int, ItemStack, int)} before insertion, and anything the hopper
 * rejects is put back into the spawner, so a full hopper can never duplicate or
 * destroy stock.
 *
 * <p>Most stocked spawners have no hopper. A region probe that finds none puts the
 * spawner on an idle back-off of {@value #IDLE_BACKOFF_PASSES} passes, so the
 * global pass stops dispatching a region task for it every 8 ticks. Placing a
 * hopper under a spawner ({@link #wake(Location)}) ends the back-off immediately;
 * any other way a hopper appears is picked up when the back-off expires.
 */
public final class HopperLink {

    /** Vanilla hoppers transfer every 8 ticks; matching that avoids a faster loop. */
    private static final long TRANSFER_PERIOD_TICKS = 8L;
    /** Passes (8 ticks each, about 6.4 s) a spawner without a hopper is skipped. */
    static final long IDLE_BACKOFF_PASSES = 16L;
    /** Every this many passes, back-off entries for removed spawners are pruned. */
    private static final long PRUNE_INTERVAL_PASSES = 64L;

    private final BravenSpawner plugin;
    private volatile ScheduledTask task;
    private volatile boolean running;
    /** spawner id -> first pass number at which the spawner is probed again. */
    private final Map<UUID, Long> idleUntil = new ConcurrentHashMap<>();
    private final AtomicLong passes = new AtomicLong();

    public HopperLink(BravenSpawner plugin) {
        this.plugin = plugin;
    }

    /** Starts the transfer timer; the timer reads the flag on every pass. */
    public void start() {
        if (task != null) return;
        running = true;
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin, ignored -> tick(), TRANSFER_PERIOD_TICKS, TRANSFER_PERIOD_TICKS);
    }

    public void stop() {
        running = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        idleUntil.clear();
    }

    /**
     * Ends the idle back-off of any spawner one or two blocks above a newly placed
     * hopper. Only touches the in-memory map, so any thread may call it.
     */
    public void wake(Location hopper) {
        if (hopper == null || hopper.getWorld() == null) return;
        for (int depth = 1; depth <= 2; depth++) {
            Spawner spawner = plugin.manager().at(hopper.clone().add(0, depth, 0));
            if (spawner != null) idleUntil.remove(spawner.id());
        }
    }

    /** Test hook: whether the spawner is currently skipped by the global pass. */
    boolean idle(UUID spawnerId) {
        Long until = idleUntil.get(spawnerId);
        return until != null && until > passes.get();
    }

    void tick() {
        if (!enabled()) return;
        long pass = passes.incrementAndGet();
        if (pass % PRUNE_INTERVAL_PASSES == 0) {
            idleUntil.keySet().removeIf(id -> plugin.manager().byId(id) == null);
        }
        for (Spawner spawner : plugin.manager().all()) {
            if (spawner == null) continue;
            Long until = idleUntil.get(spawner.id());
            if (until != null && until > pass) continue;
            if (spawner.storedCount() <= 0) continue;
            Location block = spawner.blockLocation();
            if (block == null || block.getWorld() == null) continue;
            // The hopper is in the same chunk column as the spawner, so this
            // region owns both the block read and the inventory mutation.
            Bukkit.getRegionScheduler().execute(plugin, block, () -> transfer(spawner, block, pass));
        }
    }

    private void transfer(Spawner spawner, Location center, long pass) {
        synchronized (spawner) {
            if (!enabled() || plugin.manager().byId(spawner.id()) != spawner) return;
            World world = center.getWorld();
            if (world == null || !world.isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)) {
                backOff(spawner, pass);
                return;
            }

            Block base = center.getBlock();
            boolean found = false;
            for (int depth = 1; depth <= 2; depth++) {
                Block below = base.getRelative(BlockFace.DOWN, depth);
                if (below.getType() != Material.HOPPER) continue;
                if (!(below.getState() instanceof Hopper hopper)) continue;
                found = true;
                if (moveOne(spawner, hopper)) break;
            }
            if (found) idleUntil.remove(spawner.id());
            else backOff(spawner, pass);
        }
    }

    private void backOff(Spawner spawner, long pass) {
        idleUntil.put(spawner.id(), pass + IDLE_BACKOFF_PASSES);
    }

    /**
     * Pulls the first stored stack and inserts as much as the hopper accepts.
     *
     * @return true when at least one item actually entered the hopper
     */
    private boolean moveOne(Spawner spawner, Hopper hopper) {
        if (plugin.restrictions() != null && !plugin.restrictions().rules().canTakeItems(spawner.product(), false)) return false;
        List<StockEntry> stock = spawner.stock();
        if (stock.isEmpty()) return false;

        StockEntry first = stock.get(0);
        ItemStack sample = first.template();
        int amount = (int) Math.min(first.maxStackSize(), first.quantity());
        ItemStack pulled = spawner.drainSlotIfSimilar(0, sample, amount);
        if (pulled == null || pulled.getAmount() <= 0) return false;
        int original = pulled.getAmount();

        Inventory inventory = hopper.getInventory();
        HashMap<Integer, ItemStack> leftover = inventory.addItem(pulled);

        // A placed hopper exposes its live inventory; update() is unnecessary
        // and a failure after insertion would not undo the transferred items.
        if (leftover.isEmpty()) { plugin.markDirty(spawner); return true; }

        int rejected = 0;
        for (ItemStack rest : leftover.values()) {
            if (rest == null || rest.getAmount() <= 0) continue;
            rejected += rest.getAmount();
            spawner.restore(rest, rest.getAmount());
        }
        if (rejected < original) plugin.markDirty(spawner);
        return rejected < original;
    }

    private boolean enabled() {
        return running && plugin.isEnabled() && plugin.ready()
                && plugin.getConfig().getBoolean("allow-hoppers", false);
    }
}
