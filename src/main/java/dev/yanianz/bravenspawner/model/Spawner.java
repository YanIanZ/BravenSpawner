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
    /** One slot per item identity; guarded by {@link #lock}. */
    private final List<Slot> storage = new ArrayList<>();
    private final Set<UUID> trusted = Collections.synchronizedSet(new LinkedHashSet<>());
    private final AtomicLong produced = new AtomicLong();
    private final AtomicInteger pending = new AtomicInteger();
    /**
     * Bumped by every model mutator. Backends compare it with the revision they last
     * wrote, so a change that was never reported through {@code markDirty} is still
     * persisted at the next checkpoint. Direct edits of {@link #trusted()} are not
     * counted; callers must mark those dirty.
     */
    private final AtomicLong revision = new AtomicLong();

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
    public void kind(Kind value) { this.kind = value; touch(); }

    public String product() { return product; }
    public void product(String value) { this.product = value; touch(); }

    public int stack() { return stack; }

    public void stack(int value) {
        synchronized (lock) { stack = Math.max(0, value); touch(); }
    }

    public void addStack(int delta) {
        synchronized (lock) { stack = Math.max(0, stack + delta); touch(); }
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
            touch();
            return true;
        }
    }

    /** Head-room left before {@code limit}; never negative. */
    public int roomFor(int limit) {
        synchronized (lock) { return Math.max(0, limit - stack); }
    }

    public int tier() { return tier; }
    public void tier(int value) { this.tier = Math.max(0, value); touch(); }

    public boolean hologram() { return hologram; }
    public void hologram(boolean value) { this.hologram = value; touch(); }

    public boolean corrupted() { return corrupted; }
    public long corruptedAt() { return corruptedAt; }

    public void corrupt() {
        this.corrupted = true;
        this.corruptedAt = System.currentTimeMillis();
        touch();
    }

    public void restoreCorruption(boolean corrupted, long timestamp) {
        this.corrupted = corrupted;
        this.corruptedAt = corrupted ? Math.max(0L, timestamp) : 0L;
        touch();
    }

    public void repair() {
        this.corrupted = false;
        this.corruptedAt = 0;
        touch();
    }

    public int storedXp() { return storedXp; }
    public int takeXp() {
        synchronized (lock) {
            int xp = storedXp;
            storedXp = 0;
            if (xp != 0) touch();
            return xp;
        }
    }
    public void addXp(int amount) { addXp(amount, 0); }

    public void addXp(int amount, int maximum) {
        synchronized (lock) {
            long limit = maximum <= 0 ? Integer.MAX_VALUE : maximum;
            storedXp = (int) Math.max(storedXp, Math.min(limit, (long) storedXp + Math.max(0, amount)));
            touch();
        }
    }

    public Set<UUID> trusted() { return trusted; }

    public boolean isOwner(UUID uuid) { return owner.equals(uuid); }

    public boolean canUse(UUID uuid) { return isOwner(uuid) || (uuid != null && trusted.contains(uuid)); }

    public long produced() { return produced.get(); }
    public void produced(long delta) { produced.addAndGet(delta); touch(); }

    /**
     * Virtual production credits a mob spawner could not spend yet.
     *
     * <p>A stack of 200 000 must not mean 200 000 entity spawns in one cycle, so a
     * capped number of real spawns happens per pass and the rest is carried here.
     * Item spawners do not use this: their yield is aggregated in closed form.
     */
    public int pending() { return pending.get(); }

    public void pending(int value) { pending.set(Math.max(0, value)); touch(); }

    public void addPending(int delta) {
        if (delta <= 0) return;
        pending.updateAndGet(current -> {
            long next = (long) current + delta;
            return next > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) next;
        });
        touch();
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

    /**
     * Immutable snapshot of the stored identities in storage order. Page and cell
     * indices in the GUI refer to this order.
     */
    public List<StockEntry> stock() {
        synchronized (lock) {
            List<StockEntry> copy = new ArrayList<>(storage.size());
            for (Slot slot : storage) copy.add(new StockEntry(slot.template, slot.quantity));
            return copy;
        }
    }

    /** Exact sum of every stored quantity. Deposits keep it within {@code long}. */
    public long storedCount() {
        synchronized (lock) {
            long n = 0;
            for (Slot slot : storage) n += slot.quantity;
            return n;
        }
    }

    /** Number of distinct stored identities. */
    public int distinctSlots() {
        synchronized (lock) { return storage.size(); }
    }

    /** Stored quantity of one identity; amounts on {@code item} are ignored. */
    public long quantityOf(ItemStack item) {
        if (item == null) return 0;
        synchronized (lock) {
            Slot slot = find(item);
            return slot == null ? 0 : slot.quantity;
        }
    }

    /**
     * Adds up to {@code amount} of {@code item}'s identity within {@code limits}.
     *
     * <p>All arithmetic is checked: the result never exceeds the total, per-item or
     * distinct limits, nor the {@code long} range. What is not accepted is not
     * stored; the caller decides what happens to it (production simply does not
     * create it).
     *
     * @return the accepted quantity, 0..amount
     */
    public long deposit(ItemStack item, long amount, StockLimits limits) {
        if (item == null || item.getType().isAir() || amount <= 0) return 0;
        StockLimits bounds = limits == null ? StockLimits.UNLIMITED : limits;
        synchronized (lock) {
            long total = 0;
            for (Slot slot : storage) total += slot.quantity;
            Slot slot = find(item);
            if (slot == null && storage.size() >= bounds.effectiveDistinct()) return 0;
            long existing = slot == null ? 0 : slot.quantity;
            long accepted = Math.min(amount, Math.max(0, bounds.effectiveTotal() - total));
            accepted = Math.min(accepted, Math.max(0, bounds.effectivePerItem() - existing));
            accepted = Math.min(accepted, Long.MAX_VALUE - total);
            if (accepted <= 0) return 0;
            if (slot == null) storage.add(new Slot(item, accepted));
            else slot.quantity = existing + accepted;
            touch();
            return accepted;
        }
    }

    /**
     * Puts back stock that was taken out and could not be delivered (a full
     * inventory, a rejected hopper insert, a reversed sale).
     *
     * <p>Conservation wins over capacity: gameplay limits and the distinct bound are
     * ignored, because the units already belonged to this storage. Only the
     * {@code long} range can refuse; that would need more than 9.2e18 stored items.
     *
     * @return the restored quantity
     */
    public long restore(ItemStack item, long amount) {
        if (item == null || item.getType().isAir() || amount <= 0) return 0;
        synchronized (lock) {
            long total = 0;
            for (Slot slot : storage) total += slot.quantity;
            long accepted = Math.min(amount, Long.MAX_VALUE - total);
            if (accepted <= 0) return 0;
            Slot slot = find(item);
            if (slot == null) storage.add(new Slot(item, accepted));
            else slot.quantity += accepted;
            touch();
            return accepted;
        }
    }

    /**
     * Removes up to {@code amount} of {@code identity}, by identity rather than by
     * position, so concurrent appends cannot redirect the removal.
     *
     * @return the removed quantity, 0..amount
     */
    public long take(ItemStack identity, long amount) {
        if (identity == null || amount <= 0) return 0;
        synchronized (lock) {
            Slot slot = find(identity);
            if (slot == null) return 0;
            long removed = Math.min(amount, slot.quantity);
            slot.quantity -= removed;
            if (slot.quantity <= 0) storage.remove(slot);
            touch();
            return removed;
        }
    }

    /** Removes everything and returns what was stored. */
    public List<StockEntry> drainAll() {
        synchronized (lock) {
            List<StockEntry> copy = new ArrayList<>(storage.size());
            for (Slot slot : storage) copy.add(new StockEntry(slot.template, slot.quantity));
            storage.clear();
            touch();
            return copy;
        }
    }

    /**
     * A stale GUI may drain only the identity it originally displayed at {@code index}.
     *
     * @return an item of that identity carrying the removed amount (which may exceed
     *         the vanilla stack size; callers split it), or null when nothing moved
     */
    public ItemStack drainSlotIfSimilar(int index, ItemStack expected, int amount) {
        synchronized (lock) {
            if (expected == null || amount <= 0 || index < 0 || index >= storage.size()) return null;
            Slot slot = storage.get(index);
            if (!slot.template.isSimilar(expected)) return null;
            int removed = (int) Math.min(amount, slot.quantity);
            slot.quantity -= removed;
            if (slot.quantity <= 0) storage.remove(index);
            touch();
            ItemStack out = slot.template.clone();
            out.setAmount(removed);
            return out;
        }
    }

    /**
     * Replaces the whole store, merging entries of the same identity. Used by the
     * storage backends on load; a sum beyond {@code long} is refused with
     * {@link ArithmeticException} so the record is quarantined instead of truncated.
     */
    public void replaceStock(List<StockEntry> entries) {
        List<Slot> merged = new ArrayList<>();
        if (entries != null) {
            long total = 0;
            for (StockEntry entry : entries) {
                if (entry == null) continue;
                total = Math.addExact(total, entry.quantity());
                ItemStack template = entry.template();
                Slot existing = null;
                for (Slot slot : merged) if (slot.template.isSimilar(template)) { existing = slot; break; }
                if (existing == null) merged.add(new Slot(template, entry.quantity()));
                else existing.quantity = Math.addExact(existing.quantity, entry.quantity());
            }
        }
        synchronized (lock) {
            storage.clear();
            storage.addAll(merged);
            touch();
        }
    }

    /** Caller holds {@link #lock}. */
    private Slot find(ItemStack item) {
        for (Slot slot : storage) if (slot.template.isSimilar(item)) return slot;
        return null;
    }

    /** One identity: an amount-1 template and its exact quantity. */
    private static final class Slot {
        final ItemStack template;
        long quantity;

        Slot(ItemStack item, long quantity) {
            this.template = StockEntry.one(item);
            this.quantity = quantity;
        }
    }

    /** Monotonic mutation counter; see the field comment. */
    public long revision() { return revision.get(); }

    private void touch() { revision.incrementAndGet(); }
}
