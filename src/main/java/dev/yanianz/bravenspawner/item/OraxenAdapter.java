package dev.yanianz.bravenspawner.item;

import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.items.ItemBuilder;
import org.bukkit.inventory.ItemStack;

/** Oraxen: {@code oraxen:id}. */
final class OraxenAdapter implements ItemPluginAdapter {

    @Override public void probe() {
        OraxenItems.exists("bravenspawner_probe");
    }

    @Override public ItemStack create(CustomItemRef ref) {
        ItemBuilder builder = OraxenItems.getItemById(ref.key());
        return builder == null ? null : builder.build();
    }

    @Override public String identify(ItemStack stack) {
        return OraxenItems.getIdByItem(stack);
    }
}
