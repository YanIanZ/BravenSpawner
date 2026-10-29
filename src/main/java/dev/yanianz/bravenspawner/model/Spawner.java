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
import java.util.concurrent.atomic.AtomicLong;

/**
 * One placed spawner.
 *
 * <p>Carries everything VirtualSpawner keeps per block: stack amount, owner and
 * trust list, stored loot, accumulated XP, corruption state and a production
 * counter. Mutation is guarded by a single monitor so a region thread and an
 * async save can never interleave.
 */
public final class Spawner {

    public enum Kind { MOB, ITEM }

    private final UUID id;
    private final UUID owner;
    private final String world;
    private final int x;
    private final int y;
    private final int z;

    private final Object lock = new Object();
    private final List<ItemStack> storage = new ArrayList<>();
    private final Set<UUID> trusted = Collections.synchronizedSet(new LinkedHashSet<>());
    private final AtomicLong produced = new AtomicLong();

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
    public void stack(int value) { this.stack = Math.max(0, value); }
    public void addStack(int delta) { this.stack = Math.max(0, this.stack + delta); }

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
    public void addXp(int amount) {
        synchronized (lock) { storedXp += Math.max(0, amount); }
    }

    public Set<UUID> trusted() { return trusted; }

    public boolean isOwner(UUID uuid) { return owner.equals(uuid); }

    public boolean canUse(UUID uuid) { return isOwner(uuid) || trusted.contains(uuid); }

    public long produced() { return produced.get(); }
    public void produced(long delta) { produced.addAndGet(delta); }

    public Location location() {
        var w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, x + 0.5, y + 1.0, z + 0.5);
    }

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
    public ItemStack store(ItemStack incoming, int capacity) {
        if (incoming == null || incoming.getType().isAir()) return null;
        synchronized (lock) {
            ItemStack left = incoming.clone();
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

            return left.getAmount() > 0 ? left : null;
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

    public void replaceContents(List<ItemStack> items) {
        synchronized (lock) {
            storage.clear();
            if (items != null) {
                for (ItemStack item : items) if (item != null && !item.getType().isAir()) storage.add(item);
            }
        }
    }
}
