package dev.yanianz.bravenspawner.gui;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.menu.MenuDefinition;
import dev.yanianz.bravenspawner.menu.MenuHolder;
import dev.yanianz.bravenspawner.menu.MenuService;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.sell.SellService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpawnerGuiAuthorizationTest {
    @Test void directStorageOpenRejectsAnUnauthorizedViewer() {
        BravenSpawner plugin = mock(BravenSpawner.class);
        Spawner spawner = mock(Spawner.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        try (var selling = mockStatic(SellService.class);
             var menus = mockConstruction(MenuService.class)) {
            selling.when(() -> SellService.install(plugin)).thenReturn(mock(SellService.class));
            new SpawnerGui(plugin).openStorage(player, spawner, 0);
            verifyNoInteractions(menus.constructed().getFirst());
            verify(player, never()).openInventory(any(Inventory.class));
        }
    }

    @Test void revokedAccessCannotChargeThroughAnAlreadyOpenPurchaseMenu() throws Exception {
        BravenSpawner plugin = mock(BravenSpawner.class, RETURNS_DEEP_STUBS);
        when(plugin.restrictions()).thenReturn(null);
        when(plugin.protection()).thenReturn(null);
        Spawner spawner = mock(Spawner.class);
        UUID spawnerId = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(viewer);
        when(spawner.id()).thenReturn(spawnerId);
        when(spawner.canUse(viewer)).thenReturn(true);
        when(spawner.product()).thenReturn("ZOMBIE");
        when(plugin.manager().byId(spawnerId)).thenReturn(spawner);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("menu:\n  layout: ['111111111']\nitems:\n  filler:\n    material: STONE\n    symbol: '-'\n  buy:\n    material: EMERALD\n    symbol: '1'\n    nbt: buy_1\n");
        MenuDefinition definition = MenuDefinition.parse("buy_stack", yaml.getConfigurationSection("menu"), yaml.getConfigurationSection("items"));
        AtomicReference<Map<Character, Consumer<Player>>> callbacks = new AtomicReference<>();
        try (var selling = mockStatic(SellService.class);
             var menus = mockConstruction(MenuService.class, (service, context) -> {
                 when(service.definition("buy_stack")).thenReturn(definition);
                 MenuHolder holder = mock(MenuHolder.class);
                 Inventory inventory = mock(Inventory.class);
                 when(holder.getInventory()).thenReturn(inventory);
                 when(service.session(player)).thenReturn(holder);
                 when(service.open(eq(player), eq("buy_stack"), anyString(), anyMap(), anyMap())).thenAnswer(call -> {
                     callbacks.set(call.getArgument(4));
                     return holder;
                 });
             })) {
            selling.when(() -> SellService.install(plugin)).thenReturn(mock(SellService.class));
            SpawnerGui gui = new SpawnerGui(plugin);
            gui.openBuyStack(player, spawner);
            when(spawner.canUse(viewer)).thenReturn(false);
            callbacks.get().get('1').accept(player);
            verify(player).closeInventory();
            verify(plugin.economy(), never()).withdraw(any(), anyDouble());
        }
    }
}
