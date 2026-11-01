package dev.yanianz.bravenspawner.wand;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Review finding 1: a limited wand's use is charged inside the click event, while
 * the wand is certainly in hand, not in the deferred settlement callback where a
 * slot switch would skip it.
 */
class SellWandChargeTest {
    final BravenSpawner plugin = mock(BravenSpawner.class);
    final SellWandService service = mock(SellWandService.class);
    final Player player = mock(Player.class);
    final PlayerInventory inventory = mock(PlayerInventory.class);
    final PlayerInteractEvent event = mock(PlayerInteractEvent.class);
    final ItemStack held = mock(ItemStack.class), written = mock(ItemStack.class);
    final Wand wand = mock(Wand.class), used = mock(Wand.class);
    final Spawner spawner = mock(Spawner.class);
    final UUID wandId = UUID.randomUUID();

    void clickSpawner() {
        Block block = mock(Block.class);
        Location location = mock(Location.class);
        SpawnerManager manager = mock(SpawnerManager.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.useInteractedBlock()).thenReturn(org.bukkit.event.Event.Result.DEFAULT);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getItem()).thenReturn(held);
        when(event.getPlayer()).thenReturn(player);
        when(event.getClickedBlock()).thenReturn(block);
        when(block.getLocation()).thenReturn(location);
        when(plugin.manager()).thenReturn(manager);
        when(manager.at(location)).thenReturn(spawner);
        when(service.enabled()).thenReturn(true);
        when(service.read(held)).thenReturn(wand);
        when(wand.id()).thenReturn(wandId);
        when(wand.lifetime()).thenReturn(new WandLifetime(3, 0));
        when(wand.consumeUse()).thenReturn(used);
        when(used.lifetime()).thenReturn(new WandLifetime(2, 0));
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(held);
        when(held.getAmount()).thenReturn(1);
        when(service.write(used)).thenReturn(written);
        WandDefinition definition = mock(WandDefinition.class);
        when(definition.lifetimeMessage(anyString(), anyString())).thenReturn("%uses%");
        when(service.definition()).thenReturn(definition);
        new SellWandListener(plugin, service).onInteract(event);
    }

    @Test void startedSaleChargesTheUseDuringTheClick() {
        when(service.sell(player, wand, spawner)).thenReturn(true);
        clickSpawner();
        verify(inventory).setItemInMainHand(written);
    }

    @Test void noSaleChargesNothing() {
        when(service.sell(player, wand, spawner)).thenReturn(false);
        clickSpawner();
        verify(inventory, never()).setItemInMainHand(any());
    }
}
