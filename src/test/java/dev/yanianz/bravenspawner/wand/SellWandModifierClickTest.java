package dev.yanianz.bravenspawner.wand;

import dev.yanianz.bravenspawner.BravenSpawner;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class SellWandModifierClickTest {
    @Test void applyingModifierConsumesOneCursorItemAndReplacesOnlyTheWand() {
        BravenSpawner plugin = mock(BravenSpawner.class);
        SellWandService service = mock(SellWandService.class);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        ItemStack wandItem = mock(ItemStack.class), cursor = mock(ItemStack.class), result = mock(ItemStack.class);
        Wand wand = mock(Wand.class), modified = mock(Wand.class);
        Modifier modifier = mock(Modifier.class);
        when(service.enabled()).thenReturn(true);
        when(event.getWhoClicked()).thenReturn(player);
        when(player.getInventory()).thenReturn(inventory);
        when(event.getClickedInventory()).thenReturn(inventory);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(event.getCurrentItem()).thenReturn(wandItem);
        when(wandItem.getAmount()).thenReturn(1);
        InventoryView view = mock(InventoryView.class);
        when(event.getView()).thenReturn(view);
        when(event.getCursor()).thenReturn(cursor);
        when(cursor.getAmount()).thenReturn(3);
        when(service.read(wandItem)).thenReturn(wand);
        when(service.readModifier(cursor)).thenReturn(modifier);
        when(service.applyModifier(player, wand, modifier)).thenReturn(modified);
        when(service.write(modified)).thenReturn(result);
        new SellWandListener(plugin, service).onModifierApply(event);
        verify(event).setCancelled(true);
        verify(event).setCurrentItem(result);
        verify(cursor).setAmount(2);
        verify(view).setCursor(cursor);
    }
}
