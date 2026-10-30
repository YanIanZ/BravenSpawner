package dev.yanianz.bravenspawner.boosters;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.protection.ProtectionService;
import dev.yanianz.bravenspawner.restrictions.RestrictedSpawners;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BoosterActivationTest {
    @Test void speedGrantReplacesRegisteredSpawnerScheduleImmediatelyAfterStateUpdate() {
        BravenSpawner plugin = mock(BravenSpawner.class);
        SpawnerManager manager = mock(SpawnerManager.class);
        Spawner spawner = mock(Spawner.class);
        UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
        when(plugin.manager()).thenReturn(manager);
        when(manager.byId(id)).thenReturn(spawner);
        Boosters boosters = new Boosters(plugin);
        doAnswer(invocation -> {
            assertEquals(2.0, boosters.multiplier(Boosters.Type.SPEED, owner, id));
            return null;
        }).when(manager).reschedule(spawner);
        boosters.grantSpawner(Boosters.Type.SPEED, id, 2, 10);
        verify(manager).reschedule(spawner);
    }

    @Test void personalSpeedGrantReschedulesAllOwnedSpawners() {
        BravenSpawner plugin = mock(BravenSpawner.class);
        SpawnerManager manager = mock(SpawnerManager.class);
        Spawner first = mock(Spawner.class), second = mock(Spawner.class);
        UUID owner = UUID.randomUUID();
        when(plugin.manager()).thenReturn(manager);
        when(manager.ownedBy(owner)).thenReturn(List.of(first, second));
        new Boosters(plugin).grantPlayer(Boosters.Type.SPEED, owner, 2, 10);
        verify(manager).reschedule(first);
        verify(manager).reschedule(second);
    }

    @Test void lootGrantDoesNotRestartProductionTimer() {
        BravenSpawner plugin = mock(BravenSpawner.class);
        SpawnerManager manager = mock(SpawnerManager.class);
        when(plugin.manager()).thenReturn(manager);
        new Boosters(plugin).grantSpawner(Boosters.Type.LOOT, UUID.randomUUID(), 2, 10);
        verifyNoInteractions(manager);
    }

    @Test void restrictedSpawnerRefusesActivationWithoutConsumingItem() {
        Fixture fixture = new Fixture();
        when(fixture.restrictions.canOpen(fixture.player, fixture.spawner)).thenReturn(false);
        fixture.listener.onInteract(fixture.event);
        verify(fixture.boosters, never()).grantSpawner(any(), any(), anyDouble(), anyLong());
        verify(fixture.hand, never()).setAmount(anyInt());
        verify(fixture.protection, never()).canUse(any(), any());
    }

    @Test void protectionDenialRefusesActivationWithoutConsumingItem() {
        Fixture fixture = new Fixture();
        when(fixture.restrictions.canOpen(fixture.player, fixture.spawner)).thenReturn(true);
        when(fixture.protection.canUse(fixture.player, fixture.location)).thenReturn(false);
        fixture.listener.onInteract(fixture.event);
        verify(fixture.restrictions).canOpen(fixture.player, fixture.spawner);
        verify(fixture.protection).canUse(fixture.player, fixture.location);
        verify(fixture.boosters, never()).grantSpawner(any(), any(), anyDouble(), anyLong());
        verify(fixture.hand, never()).setAmount(anyInt());
    }

    @Test void refusedGrantKeepsTheBoosterItem() {
        Fixture fixture = new Fixture();
        when(fixture.restrictions.canOpen(fixture.player, fixture.spawner)).thenReturn(true);
        when(fixture.protection.canUse(fixture.player, fixture.location)).thenReturn(true);
        when(fixture.boosters.grantSpawner(any(), any(), anyDouble(), anyLong())).thenReturn(false);
        fixture.listener.onInteract(fixture.event);
        verify(fixture.boosters).grantSpawner(any(), any(), anyDouble(), anyLong());
        verify(fixture.hand, never()).setAmount(anyInt());
    }

    @Test void acceptedGrantConsumesOneItem() {
        Fixture fixture = new Fixture();
        when(fixture.restrictions.canOpen(fixture.player, fixture.spawner)).thenReturn(true);
        when(fixture.protection.canUse(fixture.player, fixture.location)).thenReturn(true);
        when(fixture.boosters.grantSpawner(any(), any(), anyDouble(), anyLong())).thenReturn(true);
        fixture.listener.onInteract(fixture.event);
        verify(fixture.hand).setAmount(0);
    }

    static class Fixture {
        final BravenSpawner plugin = mock(BravenSpawner.class);
        final Boosters boosters = mock(Boosters.class);
        final RestrictedSpawners restrictions = mock(RestrictedSpawners.class);
        final ProtectionService protection = mock(ProtectionService.class);
        final Player player = mock(Player.class);
        final Spawner spawner = mock(Spawner.class);
        final ItemStack hand = mock(ItemStack.class);
        final PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        final Location location = mock(Location.class);
        final BoosterListener listener;
        Fixture() {
            var manager = mock(SpawnerManager.class);
            var inventory = mock(PlayerInventory.class);
            var definition = mock(BoosterDefinition.class);
            var block = mock(Block.class);
            when(plugin.manager()).thenReturn(manager);
            when(plugin.restrictions()).thenReturn(restrictions);
            when(plugin.protection()).thenReturn(protection);
            when(plugin.ready()).thenReturn(true);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);
            when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
            when(event.getItem()).thenReturn(hand);
            when(event.getPlayer()).thenReturn(player);
            when(event.getClickedBlock()).thenReturn(block);
            when(block.getLocation()).thenReturn(location);
            when(manager.at(location)).thenReturn(spawner);
            when(spawner.blockLocation()).thenReturn(location);
            when(spawner.canUse(any())).thenReturn(true);
            when(plugin.mayUse(any(), any())).thenReturn(true);
            when(spawner.id()).thenReturn(UUID.randomUUID());
            when(player.getInventory()).thenReturn(inventory);
            when(inventory.getItemInMainHand()).thenReturn(hand);
            when(hand.getAmount()).thenReturn(1);
            when(boosters.readItem(hand)).thenReturn(definition);
            when(definition.scope()).thenReturn(Boosters.Scope.NORMAL);
            when(definition.type()).thenReturn(Boosters.Type.XP);
            when(definition.multiplier()).thenReturn(2.0);
            when(definition.seconds()).thenReturn(10L);
            when(definition.duration()).thenReturn("10s");
            listener = new BoosterListener(plugin, boosters);
        }
    }
}
