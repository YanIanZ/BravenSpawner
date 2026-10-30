package dev.yanianz.bravenspawner.gui;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.menu.MenuService;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.sell.SellService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FullCollectionTest {
    @Test void fullOnlyProductCannotCollectXpBelowItsStackScaledCapacity() throws Exception {
        BravenSpawner plugin = mock(BravenSpawner.class, RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(plugin.mayUse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenCallRealMethod();
        when(plugin.events().xpMultiplier()).thenReturn(1.0);
        when(plugin.boosters().personalMultiplier(eq(dev.yanianz.bravenspawner.boosters.Boosters.Type.XP), any())).thenReturn(1.0);
        when(plugin.restrictions()).thenReturn(null);
        when(plugin.protection()).thenReturn(null);
        Spawner spawner = mock(Spawner.class);
        Player player = mock(Player.class);
        when(plugin.product(spawner).mustBeFullToCollect()).thenReturn(true);
        when(plugin.maximumXp(spawner)).thenReturn(1000);
        when(spawner.storedXp()).thenReturn(999);
        invoke(plugin, "collectXp", player, spawner);
        verify(spawner, never()).takeXp();
    }

    @Test void fullOnlyProductCannotSellBelowItsStackScaledItemCapacity() throws Exception {
        BravenSpawner plugin = mock(BravenSpawner.class, RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(plugin.mayUse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenCallRealMethod();
        when(plugin.events().xpMultiplier()).thenReturn(1.0);
        when(plugin.boosters().personalMultiplier(eq(dev.yanianz.bravenspawner.boosters.Boosters.Type.XP), any())).thenReturn(1.0);
        when(plugin.restrictions()).thenReturn(null);
        when(plugin.protection()).thenReturn(null);
        Spawner spawner = mock(Spawner.class);
        Player player = mock(Player.class);
        when(plugin.product(spawner).mustBeFullToCollect()).thenReturn(true);
        when(plugin.maximumItems(spawner)).thenReturn(2000);
        when(spawner.storedCount()).thenReturn(1999);
        invoke(plugin, "sellAll", player, spawner);
        verify(spawner, never()).drainAll();
    }

    @Test void zeroEventXpDoesNotConsumeTheBank() throws Exception {
        BravenSpawner plugin = mock(BravenSpawner.class, RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(plugin.mayUse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenCallRealMethod();
        when(plugin.events().xpMultiplier()).thenReturn(0.0);
        when(plugin.boosters().personalMultiplier(any(), any())).thenReturn(1.0);
        Spawner model = mock(Spawner.class);
        when(model.storedXp()).thenReturn(17);
        when(model.takeXp()).thenReturn(17);
        invoke(plugin, "collectXp", mock(Player.class), model);
        verify(model, never()).takeXp();
    }
    @Test void eventAndPersonalXpRoundAtTheirSeparateReferenceStages() throws Exception {
        BravenSpawner plugin = mock(BravenSpawner.class, RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(plugin.mayUse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenCallRealMethod();
        when(plugin.events().xpMultiplier()).thenReturn(1.5);
        when(plugin.boosters().personalMultiplier(any(), any())).thenReturn(1.5);
        Spawner model = mock(Spawner.class);
        when(model.storedXp()).thenReturn(1);
        when(model.takeXp()).thenReturn(1);
        Player player = mock(Player.class);
        invoke(plugin, "collectXp", player, model);
        verify(player).giveExp(3);
    }

    private void invoke(BravenSpawner plugin, String method, Player player, Spawner spawner) throws Exception {
        SellService seller = mock(SellService.class);
        when(seller.sell(any(), any())).thenReturn(new SellService.Sale(SellService.Status.DISABLED, 0, 0, ""));
        try (var selling = mockStatic(SellService.class); var menus = mockConstruction(MenuService.class)) {
            selling.when(() -> SellService.install(plugin)).thenReturn(seller);
            SpawnerGui gui = new SpawnerGui(plugin);
            var action = SpawnerGui.class.getDeclaredMethod(method, Player.class, Spawner.class);
            action.setAccessible(true);
            action.invoke(gui, player, spawner);
            if (method.equals("sellAll")) verify(seller, never()).sell(any(), any());
        }
    }
}
