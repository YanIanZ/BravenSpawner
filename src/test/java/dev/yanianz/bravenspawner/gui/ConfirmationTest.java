package dev.yanianz.bravenspawner.gui;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.menu.MenuService;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.sell.SellService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfirmationTest {
    @Test void aSecondClickOnTheSameActionConfirmsAndADifferentActionDoesNot() {
        BravenSpawner plugin = mock(BravenSpawner.class);
        org.mockito.Mockito.when(plugin.mayUse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenCallRealMethod();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Spawner spawner = mock(Spawner.class);
        when(spawner.id()).thenReturn(UUID.randomUUID());
        try (var selling = mockStatic(SellService.class); var menus = mockConstruction(MenuService.class)) {
            selling.when(() -> SellService.install(plugin)).thenReturn(mock(SellService.class));
            SpawnerGui gui = new SpawnerGui(plugin);
            assertFalse(gui.confirmed(player, spawner, GuiAction.SELL_ALL, "sell"));
            assertFalse(gui.confirmed(player, spawner, GuiAction.BUY_MAX, "buy"), "another action restarts the prompt");
            assertTrue(gui.confirmed(player, spawner, GuiAction.BUY_MAX, "buy"));
            assertFalse(gui.confirmed(player, spawner, GuiAction.BUY_MAX, "buy"), "a confirmation is used once");
        }
    }
}
