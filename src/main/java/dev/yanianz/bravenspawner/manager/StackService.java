package dev.yanianz.bravenspawner.manager;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Authorized stacking of spawner items.
 *
 * <p>Stacking transfers owned value from an item into a placed spawner, so it runs
 * as validate-then-mutate and fails closed at every step: nothing is added and the
 * item is not consumed unless authorization, item identity, the per-chunk cap and
 * the stack cap all pass. The old listener merged whatever the player was holding,
 * which let anyone feed another player's spawner and let a mismatched product split
 * its own value across two records.
 *
 * <p>One canonical representation is used for the virtual count: the
 * {@code stack} integer in the item's persistent data container. The physical
 * {@link ItemStack#getAmount()} stays at 1 for display, so merging an item is
 * always "consume this one item, add its virtual count".
 */
public final class StackService {

    /** PDC key that carries the virtual count; mirrors the key built by the plugin. */
    private static final String STACK_KEY = "stack";

    private static final String ADMIN_PERMISSION = "bravenspawner.admin";

    /** Outcome of a merge attempt. {@link Status#OK} is the only mutating case. */
    public enum Status {
        OK,
        /** Actor is neither owner, trusted nor admin. */
        NOT_AUTHORIZED,
        /** Nothing usable in hand. */
        NO_ITEM,
        /** Item is not a spawner item, or its kind/product differs from the target. */
        WRONG_ITEM,
        /** Incoming count would push the chunk over {@code spawner-limit-in-chunk}. */
        CHUNK_LIMIT,
        /** Incoming count would push this spawner over {@code stack-limit}. */
        STACK_LIMIT
    }

    /**
     * @param status     why the merge succeeded or failed
     * @param accepted   virtual units actually added (0 on failure)
     * @param stackAfter target stack after the call (unchanged on failure)
     * @param room       head-room left on the target after the call
     */
    public record Result(Status status, int accepted, int stackAfter, int room) {

        public boolean ok() {
            return status == Status.OK;
        }
    }

    private final BravenSpawner plugin;

    public StackService(BravenSpawner plugin) {
        this.plugin = plugin;
    }

    /**
     * Merges {@code item} into {@code target} when every check passes.
     *
     * <p>Checks run in a fixed order so a failed attempt leaks no information about
     * the target beyond what the actor is already allowed to see: authorization
     * first, then item identity, then the chunk cap, then the stack cap.
     */
    public Result stack(Player actor, Spawner target, ItemStack item) {
        if (plugin == null) return new Result(Status.WRONG_ITEM, 0, 0, 0);
        Settings s = plugin.settings();
        if (s == null || target == null) {
            return new Result(Status.WRONG_ITEM, 0, target == null ? 0 : target.stack(), 0);
        }
        if (actor == null) {
            return new Result(Status.NOT_AUTHORIZED, 0, target.stack(), 0);
        }
        if (item == null || item.getAmount() <= 0 || item.getType().isAir()) {
            return new Result(Status.NO_ITEM, 0, target.stack(), 0);
        }

        // 1. Authorization: owner, trusted or admin. Checked before the item is
        //    inspected so an outsider cannot use error text to probe a spawner.
        boolean admin = actor.hasPermission(ADMIN_PERMISSION);
        if (!admin && !target.canUse(actor.getUniqueId())) {
            return new Result(Status.NOT_AUTHORIZED, 0, target.stack(), 0);
        }

        // 2. Identity: exact kind and product match. Mixing kinds would create a
        //    spawner whose model and drops disagree, and merging a different
        //    product would move value between records.
        if (item.getType() != s.spawnerMaterial) {
            return new Result(Status.WRONG_ITEM, 0, target.stack(), 0);
        }
        Spawner.Kind kind = kindOf(plugin, item);
        String product = productOf(plugin, item);
        if (kind == null || product == null) {
            return new Result(Status.WRONG_ITEM, 0, target.stack(), 0);
        }
        if (kind != target.kind() || !product.equalsIgnoreCase(String.valueOf(target.product()))) {
            return new Result(Status.WRONG_ITEM, 0, target.stack(), 0);
        }

        int incoming = canonicalCount(plugin, item, s.stackLimit);

        // 3. Per-chunk cap. The target's own stack is already part of the total,
        //    so only the incoming amount can push the chunk over the limit.
        if (s.spawnerLimitInChunk > 0) {
            int chunkX = target.x() >> 4;
            int chunkZ = target.z() >> 4;
            var world = Bukkit.getWorld(target.world());
            long total = (long) plugin.manager().stackedInChunk(world, chunkX, chunkZ);
            if (total + incoming > s.spawnerLimitInChunk) {
                return new Result(Status.CHUNK_LIMIT, 0, target.stack(), 0);
            }
        }

        // 4. Stack cap. Rejected rather than clamped: a partial merge would have to
        //    decide what happens to the remainder, and the item stays untouched so
        //    the player keeps exactly what they arrived with.
        int room = target.roomFor(s.stackLimit);
        if (incoming > room) {
            return new Result(Status.STACK_LIMIT, 0, target.stack(), room);
        }
        if (!target.tryAddStack(incoming, s.stackLimit)) {
            // Lost a race with a concurrent merge; nothing was applied.
            return new Result(Status.STACK_LIMIT, 0, target.stack(), target.roomFor(s.stackLimit));
        }

        // The whole virtual count lives on one physical item, so consuming the
        // merge means consuming that item, not decrementing a display amount.
        item.setAmount(item.getAmount() - 1);
        return new Result(Status.OK, incoming, target.stack(), target.roomFor(s.stackLimit));
    }

    /**
     * The canonical virtual count for a spawner item.
     *
     * <p>Persistent data wins over the physical amount, because the amount is
     * display-only and a stack of 1 can legitimately carry 5 000 units. Values
     * outside {@code 1..max} are clamped so a corrupted or admin-issued item cannot
     * inject more value than the configured cap allows, and a missing key falls
     * back to 1 for items issued before the canonical field existed.
     */
    public static int canonicalCount(BravenSpawner plugin, ItemStack item, int max) {
        int limit = Math.max(1, max);
        if (plugin == null || item == null) return 1;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 1;
        Integer stored = meta.getPersistentDataContainer().get(stackKey(plugin), PersistentDataType.INTEGER);
        if (stored == null) return 1;
        return Math.max(1, Math.min(limit, stored));
    }

    /** Spawner kind stamped on the item, or null when the item is not a spawner. */
    public static Spawner.Kind kindOf(BravenSpawner plugin, ItemStack item) {
        String raw = readString(plugin, item, plugin == null ? null : plugin.itemKindKey());
        if (raw == null) return null;
        try {
            return Spawner.Kind.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // A hand-edited or foreign PDC value must not throw inside a listener.
            return null;
        }
    }

    /** Product id stamped on the item, or null when the item is not a spawner. */
    public static String productOf(BravenSpawner plugin, ItemStack item) {
        return readString(plugin, item, plugin == null ? null : plugin.itemProductKey());
    }

    /**
     * The shared PDC key for the virtual count.
     *
     * <p>Namespaced keys compare by value, so this resolves to the same entry the
     * plugin writes when it builds a spawner item; the constant lives here so place,
     * merge and break cannot drift apart again.
     */
    public static NamespacedKey stackKey(BravenSpawner plugin) {
        return new NamespacedKey(plugin, STACK_KEY);
    }

    private static String readString(BravenSpawner plugin, ItemStack item, NamespacedKey key) {
        if (plugin == null || item == null || key == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String value = pdc.get(key, PersistentDataType.STRING);
        return value == null || value.isBlank() ? null : value;
    }
}
