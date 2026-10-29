package dev.yanianz.bravenspawner.menu;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MenuSessionRoutingTest {
    @Test void openingSecondPlayersMenuDoesNotReplaceFirstPlayersActions() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        MenuLoader loader = mock(MenuLoader.class);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("menu:\n  layout: ['sssssssss']\nitems:\n  filler:\n    material: STONE\n    symbol: '-'\n  storage:\n    material: CHEST\n    symbol: s\n");
        MenuDefinition definition = MenuDefinition.parse("spawner", yaml.getConfigurationSection("menu"), yaml.getConfigurationSection("items"));
        when(loader.get("spawner")).thenReturn(definition);
        when(loader.directory()).thenReturn(Path.of("gui").toFile());
        MenuRenderer renderer = mock(MenuRenderer.class);
        when(renderer.render(any(), any(), any(), any())).thenAnswer(call -> {
            MenuHolder holder = call.getArgument(3);
            Inventory inventory = mock(Inventory.class);
            when(inventory.getHolder()).thenReturn(holder);
            holder.attach(inventory);
            return inventory;
        });
        MenuService service = new MenuService(plugin, loader, renderer);
        Player first = player();
        Player second = player();
        AtomicInteger firstActions = new AtomicInteger();
        AtomicInteger secondActions = new AtomicInteger();
        MenuHolder firstView = service.open(first, "spawner", Map.of(), Map.of('s', p -> firstActions.incrementAndGet()));
        service.open(second, "spawner", Map.of(), Map.of('s', p -> secondActions.incrementAndGet()));
        click(service, first, firstView);
        assertEquals(1, firstActions.get());
        assertEquals(0, secondActions.get());
        // Reloaded definitions must not reinterpret the symbols of an existing view.
        when(loader.get("spawner")).thenReturn(null);
        click(service, first, firstView);
        assertEquals(2, firstActions.get());
        when(loader.get("spawner")).thenReturn(definition);
        MenuHolder replacement = service.open(first, "spawner", Map.of(), Map.of());
        click(service, first, replacement);
        assertEquals(2, firstActions.get(), "an omitted callback must not inherit the previous window's action");
        service.closed(first, firstView);
        assertSame(replacement, service.session(first), "a stale close must preserve the replacement session");
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        InventoryView view = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(player.openInventory(any(Inventory.class))).thenAnswer(call -> {
            when(view.getTopInventory()).thenReturn(call.getArgument(0));
            return view;
        });
        return player;
    }

    private void click(MenuService service, Player player, MenuHolder holder) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = player.getOpenInventory();
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(holder.getInventory());
        when(event.getSlot()).thenReturn(0);
        new MenuListener(service).onClick(event);
    }
}
