package dev.yanianz.bravenspawner.model;

import org.bukkit.inventory.ItemStack;

/**
 * One stored item identity with its exact quantity.
 *
 * <p>The template is an amount-1 representative of the identity (material and every
 * component, compared with {@link ItemStack#isSimilar(ItemStack)}). The quantity is a
 * {@code long} and is never stored in {@link ItemStack#getAmount()}, which is an
 * {@code int} and is refused above 99 by newer Paper serializers. Instances are
 * immutable snapshots: the template is cloned on the way in and on the way out.
 */
public record StockEntry(ItemStack template, long quantity) {

    public StockEntry {
        if (template == null || template.getType().isAir()) throw new IllegalArgumentException("template must be an item");
        if (quantity < 1) throw new IllegalArgumentException("quantity " + quantity + " below 1");
        template = one(template);
    }

    /** A fresh amount-1 copy of the identity. */
    @Override public ItemStack template() { return template.clone(); }

    /** True when {@code item} has this entry's identity, ignoring amounts. */
    public boolean matches(ItemStack item) {
        return item != null && template.isSimilar(item);
    }

    /** The vanilla maximum stack size of this identity. */
    public int maxStackSize() { return Math.max(1, template.getMaxStackSize()); }

    /** A real {@link ItemStack} of this identity, {@code amount} clamped to 1..maxStackSize. */
    public ItemStack stack(long amount) {
        ItemStack out = template.clone();
        out.setAmount((int) Math.max(1L, Math.min(maxStackSize(), amount)));
        return out;
    }

    static ItemStack one(ItemStack item) {
        ItemStack copy = item.clone();
        copy.setAmount(1);
        return copy;
    }
}
