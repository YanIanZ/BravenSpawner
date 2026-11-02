package dev.yanianz.bravenspawner.item;

import dev.lone.itemsadder.api.CustomStack;
import org.bukkit.inventory.ItemStack;

/** ItemsAdder: {@code itemsadder:namespace:id}; identity is the namespaced id. */
final class ItemsAdderAdapter implements ItemPluginAdapter {

    @Override public void probe() {
        CustomStack.isInRegistry("bravenspawner:probe");
    }

    @Override public ItemStack create(CustomItemRef ref) {
        CustomStack stack = CustomStack.getInstance(ref.key());
        return stack == null ? null : stack.getItemStack();
    }

    @Override public String identify(ItemStack stack) {
        CustomStack custom = CustomStack.byItemStack(stack);
        return custom == null ? null : custom.getNamespacedID();
    }
}
