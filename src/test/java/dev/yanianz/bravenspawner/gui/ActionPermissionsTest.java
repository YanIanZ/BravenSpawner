package dev.yanianz.bravenspawner.gui;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.econ.CurrencyAction;
import dev.yanianz.bravenspawner.econ.CurrencyRegistry;
import dev.yanianz.bravenspawner.menu.MenuClick;
import dev.yanianz.bravenspawner.menu.MenuDefinition;
import dev.yanianz.bravenspawner.menu.MenuHolder;
import dev.yanianz.bravenspawner.menu.MenuService;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.sell.SellService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** REV-08 / PM-01: per-action nodes narrow menu actions and default to the old behaviour. */
class ActionPermissionsTest {

    @Test void actionsMapToTheirNodes() {
        assertEquals(ActionPermissions.SELL, ActionPermissions.of(GuiAction.SELL_ALL));
        assertEquals(ActionPermissions.COLLECT_XP, ActionPermissions.of(GuiAction.COLLECT_XP));
        assertEquals(ActionPermissions.UPGRADE, ActionPermissions.of(GuiAction.UPGRADE));
        for (GuiAction buy : new GuiAction[] {GuiAction.BUY_STACK_MENU, GuiAction.BUY_ONE, GuiAction.BUY_64, GuiAction.BUY_MAX}) {
            assertEquals(ActionPermissions.BUY_STACK, ActionPermissions.of(buy), buy.name());
        }
        // Navigation stays ungated; extraction keeps its own existing node.
        for (GuiAction free : new GuiAction[] {GuiAction.STORAGE, GuiAction.NEXT, GuiAction.BACK, GuiAction.CLOSE,
                GuiAction.TAKE_ALL, GuiAction.LOG, GuiAction.COLLECT_ALL}) {
            assertNull(ActionPermissions.of(free), free.name());
        }
    }

    @Test void pluginYmlDeclaresEveryNodeDefaultTrue() throws Exception {
        String text;
        try (var in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(in);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Permission nodes contain dots, so read them with a different separator.
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator('/');
        yaml.loadFromString(text);
        for (ActionPermissions permission : ActionPermissions.values()) {
            assertEquals(Boolean.TRUE, yaml.get("permissions/" + permission.node() + "/default"), permission.node());
            assertEquals(Boolean.TRUE, yaml.get("permissions/bravenspawner.action.*/children/" + permission.node()));
        }
        assertEquals(Boolean.TRUE, yaml.get("permissions/bravenspawner.action.*/default"));
    }

    @Test void deniedBuyNodeNeverReachesTheCurrency() throws Exception {
        assertBuyClick(false);
    }

    @Test void grantedBuyNodeProceedsToTheCurrency() throws Exception {
        assertBuyClick(true);
    }

    private void assertBuyClick(boolean granted) throws Exception {
        BravenSpawner plugin = mock(BravenSpawner.class, RETURNS_DEEP_STUBS);
        when(plugin.mayUse(any(), any())).thenCallRealMethod();
        when(plugin.restrictions()).thenReturn(null);
        when(plugin.protection()).thenReturn(null);
        Spawner spawner = mock(Spawner.class);
        UUID spawnerId = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(viewer);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.hasPermission(ActionPermissions.BUY_STACK.node())).thenReturn(granted);
        when(spawner.id()).thenReturn(spawnerId);
        when(spawner.canUse(viewer)).thenReturn(true);
        when(spawner.product()).thenReturn("ZOMBIE");
        when(plugin.manager().byId(spawnerId)).thenReturn(spawner);
        when(plugin.product(spawner).spawnerPrice()).thenReturn(10.0);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("menu:\n  layout: ['111111111']\nitems:\n  filler:\n    material: STONE\n    symbol: '-'\n  buy:\n    material: EMERALD\n    symbol: '1'\n    nbt: buy_1\n");
        MenuDefinition definition = MenuDefinition.parse("buy_stack", yaml.getConfigurationSection("menu"), yaml.getConfigurationSection("items"));
        AtomicReference<Map<Character, MenuClick>> callbacks = new AtomicReference<>();
        try (var selling = mockStatic(SellService.class);
             var currencies = mockStatic(CurrencyRegistry.class, CALLS_REAL_METHODS);
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
            currencies.when(() -> CurrencyRegistry.select(any(), any())).thenThrow(new IllegalStateException("stop"));
            currencies.clearInvocations();
            try {
                callbacks.get().get('1').click(player, ClickType.LEFT);
            } catch (IllegalStateException expected) {
                // the granted path reaches the stubbed currency lookup
            }
            currencies.verify(() -> CurrencyRegistry.select(any(), eq(CurrencyAction.BUY_STACK)),
                    granted ? atLeastOnce() : never());
            verify(player, atLeastOnce()).hasPermission(ActionPermissions.BUY_STACK.node());
            verify(plugin.economy(), never()).withdraw(any(), anyDouble());
        }
    }
}
