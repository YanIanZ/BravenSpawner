package dev.yanianz.bravenspawner.item;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.items.ItemExecutor;
import org.bukkit.inventory.ItemStack;

/** MythicMobs (and Crucible) items: {@code mythicmobs:name}. */
final class MythicMobsAdapter implements ItemPluginAdapter {

    @Override public void probe() {
        if (manager() == null) throw new IllegalStateException("MythicMobs item manager is not ready");
    }

    private static ItemExecutor manager() {
        MythicBukkit mythic = MythicBukkit.inst();
        return mythic == null ? null : mythic.getItemManager();
    }

    @Override public ItemStack create(CustomItemRef ref) {
        ItemExecutor manager = manager();
        return manager == null ? null : manager.getItemStack(ref.key());
    }

    @Override public String identify(ItemStack stack) {
        ItemExecutor manager = manager();
        return manager == null ? null : manager.getMythicTypeFromItem(stack);
    }
}
