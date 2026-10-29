package dev.yanianz.bravenspawner.manager;
import dev.yanianz.bravenspawner.BravenSpawner;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class StackItemCountTest {
    @Test void loweringLimitDoesNotRewriteTheOwnedItemCount() {
        BravenSpawner plugin = mock(BravenSpawner.class);
        when(plugin.getName()).thenReturn("BravenSpawner");
        ItemStack item = mock(ItemStack.class, RETURNS_DEEP_STUBS);
        when(item.getItemMeta().getPersistentDataContainer().get(any(NamespacedKey.class), eq(PersistentDataType.INTEGER))).thenReturn(5000);
        assertEquals(5000, StackService.canonicalCount(plugin, item, 1000));
    }
}
