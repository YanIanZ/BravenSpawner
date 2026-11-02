package dev.yanianz.bravenspawner.item;

import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.Type;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/** MMOItems: {@code mmoitems:TYPE:ID}. */
final class MmoItemsAdapter implements ItemPluginAdapter {

    @Override public void probe() {
        if (MMOItems.plugin == null) throw new IllegalStateException("MMOItems.plugin is not initialised");
    }

    @Override public ItemStack create(CustomItemRef ref) {
        MMOItems mmo = MMOItems.plugin;
        return mmo == null ? null : mmo.getItem(ref.mmoType(), ref.mmoId());
    }

    @Override public String identify(ItemStack stack) {
        Type type = MMOItems.getType(stack);
        String id = MMOItems.getID(stack);
        if (type == null || id == null) return null;
        return type.getId().toUpperCase(Locale.ROOT) + ":" + id.toUpperCase(Locale.ROOT);
    }
}
