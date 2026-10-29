package dev.yanianz.bravemspawner.mechanics;

import dev.yanianz.bravemspawner.BravemSpawner;
import dev.yanianz.bravemspawner.model.Spawner;
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

/**
 * Emulates a hopper pulling from a virtual spawner.
 *
 * <p>The spawner block is decorative; there is no real container inventory for a
 * vanilla hopper to read. A periodic pass finds hoppers one or two blocks below a
 * spawner and moves a single stack into the hopper. Items are removed with
 * {@link Spawner#drainSlot(int, int)} before insertion, and anything the hopper
 * rejects is put back into the spawner, so a full hopper can never duplicate or
 * destroy stock.
 */
public final class HopperLink {

    /** Vanilla hoppers transfer every 8 ticks; matching that avoids a faster loop. */
    private static final long TRANSFER_PERIOD_TICKS = 8L;

    private final BravemSpawner plugin;
    private ScheduledTask task;

    public HopperLink(BravemSpawner plugin) {
        this.plugin = plugin;
    }

    /** Starts the transfer timer; the timer reads the flag on every pass. */
    public void start() {
        if (task != null) return;
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin, ignored -> tick(), TRANSFER_PERIOD_TICKS, TRANSFER_PERIOD_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (!enabled()) return;
        for (Spawner spawner : plugin.manager().all()) {
            if (spawner == null || spawner.storedCount() <= 0) continue;
            Location block = spawner.blockLocation();
            if (block == null || block.getWorld() == null) continue;
            if (!block.getWorld().isChunkLoaded(block.getBlockX() >> 4, block.getBlockZ() >> 4)) continue;
            // The hopper is in the same chunk column as the spawner, so this
            // region owns both the block read and the inventory mutation.
            Bukkit.getRegionScheduler().execute(plugin, block, () -> transfer(spawner, block));
        }
    }

    private void transfer(Spawner spawner, Location center) {
        if (!enabled() || plugin.manager().byId(spawner.id()) == null) return;
        World world = center.getWorld();
        if (world == null || !world.isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)) return;

        Block base = center.getBlock();
        for (int depth = 1; depth <= 2; depth++) {
            Block below = base.getRelative(BlockFace.DOWN, depth);
            if (below.getType() != Material.HOPPER) continue;
            if (!(below.getState() instanceof Hopper hopper)) continue;
            if (moveOne(spawner, hopper.getInventory(), below.getLocation())) return;
        }
    }

    /**
     * Pulls the first stored stack and inserts as much as the hopper accepts.
     *
     * @return true when at least one item actually entered the hopper
     */
    private boolean moveOne(Spawner spawner, Inventory inventory, Location hopperLocation) {
        List<ItemStack> contents = spawner.contents();
        if (contents.isEmpty()) return false;

        ItemStack sample = contents.get(0);
        if (sample == null || sample.getType().isAir()) return false;

        int amount = Math.min(Math.max(1, sample.getMaxStackSize()), Math.max(1, sample.getAmount()));
        ItemStack pulled = spawner.drainSlot(0, amount);
        if (pulled == null || pulled.getAmount() <= 0) return false;

        HashMap<Integer, ItemStack> leftover = inventory.addItem(pulled);
        if (leftover.isEmpty()) return true;

        int rejected = 0;
        for (ItemStack rest : leftover.values()) {
            if (rest == null || rest.getAmount() <= 0) continue;
            rejected += rest.getAmount();
            ItemStack unplaced = spawner.store(rest, 0);
            if (unplaced != null && unplaced.getAmount() > 0) {
                World world = hopperLocation.getWorld();
                if (world != null) {
                    world.dropItemNaturally(hopperLocation.clone().add(0.5, 0.5, 0.5), unplaced);
                }
            }
        }
        return rejected < pulled.getAmount();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("allow-hoppers", false);
    }
}
