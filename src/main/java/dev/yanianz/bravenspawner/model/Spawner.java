package dev.yanianz.bravenspawner.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One placed spawner.
 *
 * <p>Carries everything VirtualSpawner keeps per block: stack amount, owner and
 * trust list, stored loot, accumulated XP, corruption state and a production
 * counter. Mutation is guarded by a single monitor so a region thread and an
 * async save can never interleave.
 *
 * <p>Stack mutation is exposed as an atomic check-and-add ({@link #tryAddStack})
 * rather than a plain setter: stacking is a transfer of owned value, so the cap
 * check and the write must happen under the same lock or a concurrent merge can
 * push the stack past the configured limit.
 */
public final class Spawner {

    public enum Kind { MOB, ITEM }

    private final UUID id;
    private final UUID owner;
    private final String world;
    private final int x;
    private final int y;
    private final int z;

    private final Object lock = this;
    private final List<ItemStack> storage = new ArrayList<>();
    private final Set<UUID> trusted = Collections.synchronizedSet(new LinkedHashSet<>());
    private final AtomicLong produced = new AtomicLong();
    private final AtomicInteger pending = new AtomicInteger();

    private volatile Kind kind;
    private volatile String product;
    private volatile int stack;
    private volatile int tier;
    private volatile boolean hologram;
    private volatile boolean corrupted;
    private volatile long corruptedAt;
    private volatile int storedXp;

    public Spawner(UUID id, UUID owner, String world, int x, int y, int z,
                   Kind kind, String product, int stack, int tier, boolean hologram) {
        this.id = id;
        this.owner = owner;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.kind = kind;
        this.product = product;
        this.stack = Math.max(1, stack);
        this.tier = tier;
        this.hologram = hologram;
    }

    public UUID id() { return id; }
    public UUID owner() { return owner; }
    public String world() { return world; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }

    public Kind kind() { return kind; }
    public void kind(Kind value) { this.kind = value; }

    public String product() { return product; }
    public void product(String value) { this.product = value; }

    public int stack() { return stack; }

    public void stack(int value) {
        synchronized (lock) { stack = Math.max(0, value); }
    }

    public void addStack(int delta) {
        synchronized (lock) { stack = Math.max(0, stack + delta); }
    }

    /**
     * Adds {@code delta} only when the resulting stack stays within {@code limit}.
     *
     * <p>Returns {@code false} and changes nothing when the mount does not fit, so
     * callers can reject a merge before touching the player's item. An accepted
     * merge reports {@code true} and a fully applied delta; there is no partial
     * application, because a partial merge would need a second accounting path for
     * the leftover.
     */
    public boolean tryAddStack(int delta, int limit) {
        if (delta <= 0) return false;
        synchronized (lock) {
            long target = (long) stack + delta;
            if (target > limit) return false;
            stack = (int) target;
            return true;
        }
    }

    /** Head-room left before {@code limit}; never negative. */
    public int roomFor(int limit) {
        synchronized (lock) { return Math.max(0, limit - stack); }
    }

    public int tier() { return tier; }
    public void tier(int value) { this.tier = Math.max(0, value); }

    public boolean hologram() { return hologram; }
    public void hologram(boolean value) { this.hologram = value; }

    public boolean corrupted() { return corrupted; }
    public long corruptedAt() { return corruptedAt; }

    public void corrupt() {
        this.corrupted = true;
        this.corruptedAt = System.currentTimeMillis();
    }

    public void repair() {
        this.corrupted = false;
        this.corruptedAt = 0;
    }

    public int storedXp() { return storedXp; }
    public int takeXp() {
        synchronized (lock) {
            int xp = storedXp;
            storedXp = 0;
            return xp;
        }
    }
    public void addXp(int amount) { addXp(amount, 0); }

    public void addXp(int amount, int maximum) {
        synchronized (lock) {
            long limit = maximum <= 0 ? Integer.MAX_VALUE : maximum;
            storedXp = (int) Math.max(storedXp, Math.min(limit, (long) storedXp + Math.max(0, amount)));
        }
    }

    public Set<UUID> trusted() { return trusted; }

    public boolean isOwner(UUID uuid) { return owner.equals(uuid); }

    public boolean canUse(UUID uuid) { return isOwner(uuid) || (uuid != null && trusted.contains(uuid)); }

    public long produced() { return produced.get(); }
    public void produced(long delta) { produced.addAndGet(delta); }

    /**
     * Virtual production credits a mob spawner could not spend yet.
     *
     * <p>A stack of 200 000 must not mean 200 000 entity spawns in one cycle, so a
     * capped number of real spawns happens per pass and the rest is carried here.
     * Item spawners do not use this: their yield is aggregated in closed form.
     */
    public int pending() { return pending.get(); }

    public void pending(int value) { pending.set(Math.max(0, value)); }

    public void addPending(int delta) {
        if (delta <= 0) return;
        pending.updateAndGet(current -> {
            long next = (long) current + delta;
            return next > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) next;
        });
    }

    /**
     * A location snapshot for this spawner block.
     *
     * <p>The returned object may be built from any thread (world lookup is a map
     * read), but it must only be used to dispatch work to the owning region — never
     * as a licence to read blocks or entities from a foreign thread.
     */
    public Location location() {
        var w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, x + 0.5, y + 1.0, z + 0.5);
    }

    /** The block's own location; same thread-ownership rule as {@link #location()}. */
    public Location blockLocation() {
        var w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, x, y, z);
    }

    public List<ItemStack> contents() {
        synchronized (lock) {
            List<ItemStack> copy = new ArrayList<>(storage.size());
            for (ItemStack item : storage) copy.add(item.clone());
            return copy;
        }
    }

    public int storedCount() {
        synchronized (lock) {
            int n = 0;
            for (ItemStack item : storage) n += item.getAmount();
            return n;
        }
    }

    public int distinctSlots() {
        synchronized (lock) { return storage.size(); }
    }

    /**
     * Inserts as much as fits. Returns the remainder, or null when everything landed.
     * Capacity 0 (or negative) means unlimited storage.
     */
    public ItemStack store(ItemStack incoming, int capacity) { return store(incoming, capacity, 0); }

    public ItemStack store(ItemStack incoming, int capacity, int maximumItems) {
        if (incoming == null || incoming.getType().isAir()) return null;
        synchronized (lock) {
            ItemStack left = incoming.clone();
            int rejected = 0;
            if (maximumItems > 0) {
                long total = 0;
                for (ItemStack item : storage) total += item.getAmount();
                int admitted = (int) Math.min(left.getAmount(), Math.max(0L, maximumItems - total));
                rejected = left.getAmount() - admitted;
                left.setAmount(admitted);
            }
            int limit = capacity <= 0 ? Integer.MAX_VALUE : capacity;

            for (ItemStack existing : storage) {
                if (left.getAmount() <= 0) break;
                if (!existing.isSimilar(left)) continue;
                int space = existing.getMaxStackSize() - existing.getAmount();
                if (space <= 0) continue;
                int move = Math.min(space, left.getAmount());
                existing.setAmount(existing.getAmount() + move);
                left.setAmount(left.getAmount() - move);
            }

            while (left.getAmount() > 0 && storage.size() < limit) {
                ItemStack chunk = left.clone();
                int take = Math.min(chunk.getMaxStackSize(), left.getAmount());
                chunk.setAmount(take);
                storage.add(chunk);
                left.setAmount(left.getAmount() - take);
            }

            left.setAmount(left.getAmount() + rejected);
            return left.getAmount() > 0 ? left : null;
        }
    }

    /** Virtual storage uses one logical count per item identity, independent of vanilla stack size. */
    public ItemStack storeVirtual(ItemStack incoming, int maximumItems) {
        if (incoming == null || incoming.getType().isAir() || incoming.getAmount() <= 0) return null;
        synchronized (lock) {
            long total = 0;
            for (ItemStack item : storage) total += item.getAmount();
            long ceiling = maximumItems <= 0 ? Integer.MAX_VALUE : maximumItems;
            int admitted = (int) Math.min(incoming.getAmount(), Math.max(0L, ceiling - total));
            int remaining = admitted;
            for (ItemStack item : storage) {
                if (remaining <= 0) break;
                if (!item.isSimilar(incoming)) continue;
                int moved = (int) Math.min(remaining, (long) Integer.MAX_VALUE - item.getAmount());
                item.setAmount(item.getAmount() + moved);
                remaining -= moved;
            }
            if (remaining > 0) {
                ItemStack entry = incoming.clone();
                entry.setAmount(remaining);
                storage.add(entry);
            }
            int rejected = incoming.getAmount() - admitted;
            if (rejected <= 0) return null;
            ItemStack leftover = incoming.clone();
            leftover.setAmount(rejected);
            return leftover;
        }
    }

    public List<ItemStack> drainAll() {
        synchronized (lock) {
            List<ItemStack> copy = new ArrayList<>(storage);
            storage.clear();
            return copy;
        }
    }

    /** Removes up to {@code amount} from a stored slot; used by the paged GUI. */
    public ItemStack drainSlot(int index, int amount) {
        synchronized (lock) {
            if (index < 0 || index >= storage.size()) return null;
            ItemStack slot = storage.get(index);
            int take = Math.min(amount, slot.getAmount());
            ItemStack out = slot.clone();
            out.setAmount(take);
            slot.setAmount(slot.getAmount() - take);
            if (slot.getAmount() <= 0) storage.remove(index);
            return out;
        }
    }

    /** A stale GUI may drain only the same item identity it originally displayed. */
    public ItemStack drainSlotIfSimilar(int index, ItemStack expected, int amount) {
        synchronized (lock) {
            if (expected == null || amount <= 0 || index < 0 || index >= storage.size()) return null;
            if (!storage.get(index).isSimilar(expected)) return null;
            return drainSlot(index, amount);
        }
    }

    public void replaceContents(List<ItemStack> items) {
        synchronized (lock) {
            storage.clear();
            if (items != null) {
                for (ItemStack item : items) if (item != null && !item.getType().isAir()) storage.add(item);
            }
        }
    }
}
