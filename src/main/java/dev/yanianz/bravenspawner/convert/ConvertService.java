package dev.yanianz.bravenspawner.convert;

import dev.yanianz.bravenspawner.audit.AuditEvent;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Side-effect-free conversion helpers shared by every conversion path.
 *
 * <p>Owner attribution: conversions with a known placer keep that placer (the
 * existing place path). Conversions found in the world (chunk load, admin
 * scan) have no trustworthy placer, so they are owned by {@link #SYSTEM_OWNER},
 * the nil UUID also used as the audit system actor. It is never null —
 * storage columns are {@code NOT NULL} and {@code Spawner#isOwner} would throw
 * on a null owner — and it never equals a real player, so a conversion is
 * never attributed to a random player. While ownership is enforced only
 * administrators can use or break system-owned spawners; with
 * {@code ownership.enabled: false} anyone can.
 */
public final class ConvertService {

    /** Owner stamped on conversions with no known placer. Never null, never a player. */
    public static final UUID SYSTEM_OWNER = AuditEvent.SYSTEM_ACTOR;

    /** Chunk-load retries after a throttled pass: 3 attempts, 20 ticks apart. */
    public static final int MAX_RETRIES = 3;
    public static final long RETRY_DELAY_TICKS = 20L;

    private ConvertService() {
    }

    /**
     * Owner for a new managed spawner: the placer when known, else the system owner.
     */
    public static UUID ownerFor(UUID placer) {
        return placer != null ? placer : SYSTEM_OWNER;
    }

    /**
     * Product id for a vanilla spawner block, or null when the block is not a
     * spawner, has no mob, or the mob has no product.
     *
     * <p>Mirrors the place path: a mob without a product stays vanilla and is
     * never silently remapped to another mob.
     *
     * @param block  the block to inspect, on its region thread
     * @param lookup maps an EntityType name to a product id, or null when none exists
     */
    public static String productForBlock(Block block, Function<String, String> lookup) {
        if (block == null || lookup == null) {
            return null;
        }
        if (block.getType() != Material.SPAWNER) {
            return null;
        }
        BlockState state;
        try {
            state = block.getState();
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (!(state instanceof CreatureSpawner spawner)) {
            return null;
        }
        EntityType type;
        try {
            type = spawner.getSpawnedType();
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (type == null) {
            return null;
        }
        try {
            return lookup.apply(type.name());
        } catch (RuntimeException broken) {
            return null;
        }
    }

    /**
     * Mob carried by a spawner item: SilkSpawners NBT first, then the vanilla
     * BlockState (a spawner item with a BlockEntityTag), else empty.
     *
     * <p>A plain spawner item carries no mob and yields empty: it stays
     * untouched rather than being guessed into a product.
     */
    public static Optional<String> mobForItem(ItemStack item) {
        if (item == null) {
            return Optional.empty();
        }
        Optional<String> silk = SilkSpawnersNbt.readMob(item);
        if (silk.isPresent()) {
            return silk;
        }
        ItemMeta meta;
        try {
            meta = item.getItemMeta();
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
        if (!(meta instanceof BlockStateMeta blockMeta)) {
            return Optional.empty();
        }
        boolean present;
        try {
            present = blockMeta.hasBlockState();
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
        if (!present) {
            return Optional.empty();
        }
        BlockState state;
        try {
            state = blockMeta.getBlockState();
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
        if (!(state instanceof CreatureSpawner spawner)) {
            return Optional.empty();
        }
        EntityType type;
        try {
            type = spawner.getSpawnedType();
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
        return type == null ? Optional.empty() : Optional.of(type.name());
    }

    /**
     * Whether one more conversion fits the chunk cap, counting conversions
     * already performed in the same chunk pass.
     *
     * <p>Each conversion adds one block of one stack unit. In the default
     * block-count mode a pass that already converted up to the cap refuses the
     * next spawner even though the manager's own count has not caught up yet;
     * in stacks mode the same holds for units. A zero or negative limit means
     * no cap, exactly like {@link SpawnerManager#chunkAllowsNewSpawner}.
     *
     * @param pending conversions already done in this chunk pass
     */
    public static boolean chunkAllowsWithPending(SpawnerManager manager, World world,
                                                 int chunkX, int chunkZ, int pending) {
        if (manager == null) {
            return false;
        }
        Settings settings = manager.settings();
        if (settings == null || settings.spawnerLimitInChunk <= 0) {
            return true;
        }
        long extra = (long) Math.max(0, pending) + 1L;
        if (!settings.chunkLimitCountsStacks) {
            return (long) manager.spawnersInChunk(world, chunkX, chunkZ) + extra <= settings.spawnerLimitInChunk;
        }
        return (long) manager.stackedInChunk(world, chunkX, chunkZ) + extra <= settings.spawnerLimitInChunk;
    }

    /**
     * Whether a chunk-load pass with throttled leftovers deserves one more
     * bounded retry. Only throttle schedules a retry: every other skip reason
     * is stable and re-running would just burn region ticks.
     */
    public static boolean shouldRetry(int throttledSkips, int attempt) {
        return throttledSkips > 0 && attempt < MAX_RETRIES;
    }
}
