package dev.yanianz.bravenspawner.item;

import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;
import org.bukkit.inventory.ItemStack;

/** Nexo: {@code nexo:id}. */
final class NexoAdapter implements ItemPluginAdapter {

    @Override public void probe() {
        NexoItems.exists("bravenspawner_probe");
    }

    @Override public ItemStack create(CustomItemRef ref) {
        ItemBuilder builder = NexoItems.itemFromId(ref.key());
        return builder == null ? null : builder.build();
    }

    @Override public String identify(ItemStack stack) {
        return NexoItems.idFromItem(stack);
    }
}
