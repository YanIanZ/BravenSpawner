package dev.yanianz.bravenspawner.item;

import com.willfp.eco.core.items.CustomItem;
import com.willfp.eco.core.items.Items;
import com.willfp.eco.core.items.TestableItem;
import org.bukkit.inventory.ItemStack;

/**
 * EcoItems: {@code ecoitems:id}, through eco's item registry where EcoItems
 * registers every item under the {@code ecoitems} namespace.
 */
final class EcoItemsAdapter implements ItemPluginAdapter {

    private static final String NAMESPACE = "ecoitems";

    @Override public void probe() {
        Items.getCustomItems();
    }

    @Override public ItemStack create(CustomItemRef ref) {
        TestableItem item = Items.lookup(NAMESPACE + ":" + ref.key());
        return item == null ? null : item.getItem();
    }

    @Override public String identify(ItemStack stack) {
        CustomItem item = Items.getCustomItem(stack);
        if (item == null || !NAMESPACE.equals(item.getKey().getNamespace())) return null;
        return item.getKey().getKey();
    }
}
