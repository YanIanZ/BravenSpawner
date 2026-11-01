package dev.yanianz.bravenspawner.gui;

import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.model.StockEntry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Moves drained storage into a player inventory without ever creating an
 * oversized stack.
 *
 * <p>Storage keeps one logical stack per item (500 ender pearls is one entry).
 * Handing that to {@code addItem} relies on the server splitting it by the item's
 * own maximum, which is not guaranteed; here every stack is split by
 * {@link ItemStack#getMaxStackSize()} first. Only as many stacks as an inventory
 * could hold are materialised; the rest, and anything that did not fit, goes
 * straight back into storage.
 */
final class Withdrawals {

    /** More stacks than any player inventory can take in one call. */
    static final int MAX_PARTS = 41;

    record Result(long moved, long returned) { }

    private Withdrawals() {}

    /**
     * Splits stacks by their own maximum size.
     *
     * @param overflow receives the remainder of a stack beyond {@code maxParts}
     *                 parts, as one logical stack
     */
    static List<ItemStack> split(List<ItemStack> items, int maxParts, List<ItemStack> overflow) {
        List<ItemStack> parts = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getAmount() <= 0) continue;
            int max = Math.max(1, item.getMaxStackSize());
            int left = item.getAmount();
            while (left > 0 && parts.size() < maxParts) {
                int size = Math.min(max, left);
                ItemStack part = item.clone();
                part.setAmount(size);
                parts.add(part);
                left -= size;
            }
            if (left > 0) {
                ItemStack rest = item.clone();
                rest.setAmount(left);
                overflow.add(rest);
            }
        }
        return parts;
    }

    /**
     * Moves as much stored stock as one inventory can take, by identity.
     *
     * <p>At most {@link #MAX_PARTS} stacks are taken out, so a store of millions
     * never materialises millions of {@link ItemStack}s; whatever the inventory
     * rejects is restored to the same identity.
     *
     * @return the moved count and everything still stored afterwards, so callers can
     *         report "left behind" for the whole store, not just the rejected batch
     */
    static Result takeAll(Player player, Spawner spawner) {
        List<ItemStack> taken = new ArrayList<>();
        int parts = 0;
        for (StockEntry entry : spawner.stock()) {
            if (parts >= MAX_PARTS) break;
            long want = Math.min(entry.quantity(), (long) (MAX_PARTS - parts) * entry.maxStackSize());
            long removed = spawner.take(entry.template(), want);
            if (removed <= 0) continue;
            ItemStack drained = entry.template();
            drained.setAmount((int) removed);
            taken.add(drained);
            parts += (int) ((removed + entry.maxStackSize() - 1) / entry.maxStackSize());
        }
        long moved = taken.isEmpty() ? 0 : deliver(player, spawner, taken).moved();
        return new Result(moved, spawner.storedCount());
    }

    /** Gives drained items to the player; whatever does not fit is stored back. */
    static Result deliver(Player player, Spawner spawner, List<ItemStack> drained) {
        List<ItemStack> overflow = new ArrayList<>();
        List<ItemStack> parts = split(drained, MAX_PARTS, overflow);
        long moved = 0;
        long returned = 0;
        if (!parts.isEmpty()) {
            long offered = 0;
            for (ItemStack part : parts) offered += part.getAmount();
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(parts.toArray(new ItemStack[0]));
            long rejected = 0;
            if (leftover != null) {
                for (ItemStack item : leftover.values()) {
                    rejected += item.getAmount();
                    spawner.restore(item, item.getAmount());
                }
            }
            moved = offered - rejected;
            returned += rejected;
        }
        for (ItemStack item : overflow) {
            returned += item.getAmount();
            spawner.restore(item, item.getAmount());
        }
        return new Result(moved, returned);
    }
}
