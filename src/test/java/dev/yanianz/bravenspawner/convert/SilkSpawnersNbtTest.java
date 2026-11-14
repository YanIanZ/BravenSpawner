package dev.yanianz.bravenspawner.convert;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SilkSpawners NBT detection without a SilkSpawners dependency. */
class SilkSpawnersNbtTest {

    private static ItemStack itemWithKeys(Set<NamespacedKey> keys, String value) {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.getKeys()).thenReturn(keys);
        for (NamespacedKey key : keys) {
            when(pdc.get(key, PersistentDataType.STRING)).thenReturn(value);
        }
        return item;
    }

    @Test void silkSpawnersKeyYieldsUpperCasedMob() {
        ItemStack item = itemWithKeys(Set.of(new NamespacedKey("silkspawners", "entity")), "  skeleton ");
        assertEquals("SKELETON", SilkSpawnersNbt.readMob(item).orElseThrow());
    }

    @Test void anyKeyInTheSilkNamespaceCounts() {
        ItemStack item = itemWithKeys(Set.of(new NamespacedKey("silkspawners", "spawner-type")), "zombie");
        assertEquals("ZOMBIE", SilkSpawnersNbt.readMob(item).orElseThrow());
    }

    @Test void foreignNamespacesAreIgnored() {
        ItemStack item = itemWithKeys(Set.of(new NamespacedKey("bravenspawner", "product")), "ZOMBIE");
        assertTrue(SilkSpawnersNbt.readMob(item).isEmpty());
    }

    @Test void malformedValueYieldsEmptyInsteadOfThrowing() {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        NamespacedKey key = new NamespacedKey("silkspawners", "entity");
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.getKeys()).thenReturn(Set.of(key));
        when(pdc.get(key, PersistentDataType.STRING)).thenThrow(new IllegalArgumentException("wrong type"));
        assertTrue(SilkSpawnersNbt.readMob(item).isEmpty());
    }

    @Test void blankValueYieldsEmpty() {
        ItemStack item = itemWithKeys(Set.of(new NamespacedKey("silkspawners", "entity")), "   ");
        assertTrue(SilkSpawnersNbt.readMob(item).isEmpty());
    }

    @Test void unreadableContainerYieldsEmpty() {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenThrow(new IllegalStateException("gone"));
        assertTrue(SilkSpawnersNbt.readMob(item).isEmpty());
        assertTrue(SilkSpawnersNbt.readMob(null).isEmpty());
        ItemStack noMeta = mock(ItemStack.class);
        when(noMeta.getItemMeta()).thenReturn(null);
        assertTrue(SilkSpawnersNbt.readMob(noMeta).isEmpty());
    }
}
