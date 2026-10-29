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
        Spawner spawner = mock(Spawner.class);
        Player player = mock(Player.class);
        when(plugin.product(spawner).mustBeFullToCollect()).thenReturn(true);
        when(plugin.maximumItems(spawner)).thenReturn(2000);
        when(spawner.storedCount()).thenReturn(1999);
        invoke(plugin, "sellAll", player, spawner);
        verify(spawner, never()).drainAll();
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
