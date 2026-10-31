package dev.yanianz.bravenspawner.gui;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.menu.*;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.sell.SellService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StorageWithdrawalTest {
    @Test void leftClickWithdrawsOneFromTheDisplayedAuthoritativeStack() throws Exception { withdraw(ClickType.LEFT, 20, 16, 1); }
    @Test void shiftLeftClickWithdrawsTheDisplayedStack() throws Exception { withdraw(ClickType.SHIFT_LEFT, 20, 64, 20); }
    @Test void shiftClickIsCappedAtOneVanillaStack() throws Exception { withdraw(ClickType.SHIFT_LEFT, 500, 16, 16); }
    @Test void rightClickTakesSixtyFourAsTheShippedLoreSays() throws Exception { withdraw(ClickType.RIGHT, 500, 16, 64); }

    @Test void closedInventoryDiscardsQueuedWithdrawal() throws Exception { withdraw(ClickType.LEFT, 20, 16, 1, "closed"); }
    @Test void disconnectedPlayerDiscardsQueuedWithdrawal() throws Exception { withdraw(ClickType.LEFT, 20, 16, 1, "offline"); }
    @Test void revokedAccessDiscardsQueuedWithdrawal() throws Exception { withdraw(ClickType.LEFT, 20, 16, 1, "revoked"); }
    @Test void removedSpawnerDiscardsQueuedWithdrawal() throws Exception { withdraw(ClickType.LEFT, 20, 16, 1, "removed"); }

    private void withdraw(ClickType click, int stored, int maxStack, int amount) throws Exception {
        withdraw(click, stored, maxStack, amount, "");
    }

    private void withdraw(ClickType click, int stored, int maxStack, int amount, String invalidation) throws Exception {
        BravenSpawner plugin = mock(BravenSpawner.class, RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(plugin.mayUse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenCallRealMethod();
        when(plugin.restrictions()).thenReturn(null);
        when(plugin.protection()).thenReturn(null);
        Spawner spawner = mock(Spawner.class);
        Player player = mock(Player.class, RETURNS_DEEP_STUBS);
        Queue<Runnable> tasks = new ArrayDeque<>();
        when(player.isOnline()).thenReturn(true);
        when(player.getScheduler().execute(any(), any(), nullable(Runnable.class), eq(1L))).thenAnswer(call -> {
            tasks.add(call.getArgument(1));
            return true;
        });
        UUID viewer = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(viewer);
        when(player.hasPermission("bravenspawner.item.extract")).thenReturn(true);
        when(spawner.id()).thenReturn(id);
        when(spawner.product()).thenReturn("ZOMBIE");
        when(spawner.canUse(viewer)).thenReturn(true);
        when(plugin.manager().byId(id)).thenReturn(spawner);
        ItemStack template = mock(ItemStack.class);
        when(template.clone()).thenReturn(template);
        when(template.getAmount()).thenReturn(stored);
        when(template.getMaxStackSize()).thenReturn(maxStack);
        when(spawner.contents()).thenReturn(List.of(template));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("menu:\n  layout: ['---------', '---------']\nitems:\n  filler:\n    material: STONE\n    symbol: '-'\n");
        MenuDefinition definition = MenuDefinition.parse("storage", yaml.getConfigurationSection("menu"), yaml.getConfigurationSection("items"));
        MenuHolder holder = mock(MenuHolder.class);
        Inventory inventory = mock(Inventory.class);
        when(holder.getInventory()).thenReturn(inventory);
        when(inventory.getHolder()).thenReturn(holder);
        try (var selling = mockStatic(SellService.class);
             var menus = mockConstruction(MenuService.class, (service, context) -> {
                 when(service.definition("storage")).thenReturn(definition);
                 when(service.session(player)).thenReturn(null, holder);
                 when(service.open(eq(player), eq("storage"), anyString(), anyMap(), anyMap())).thenReturn(holder);
             })) {
            selling.when(() -> SellService.install(plugin)).thenReturn(mock(SellService.class));
            SpawnerGui gui = new SpawnerGui(plugin);
            gui.openStorage(player, spawner, 0);
            InventoryView view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(inventory);
            when(player.getOpenInventory()).thenReturn(view);
            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getView()).thenReturn(view);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getClickedInventory()).thenReturn(inventory);
            when(event.getSlot()).thenReturn(0);
            when(event.getClick()).thenReturn(click);
            gui.onClick(event);
            verify(spawner, never()).drainSlotIfSimilar(anyInt(), any(), anyInt());
            assertEquals(1, tasks.size());
            switch (invalidation) {
                case "closed" -> when(view.getTopInventory()).thenReturn(mock(Inventory.class));
                case "offline" -> when(player.isOnline()).thenReturn(false);
                case "revoked" -> when(spawner.canUse(viewer)).thenReturn(false);
                case "removed" -> when(plugin.manager().byId(id)).thenReturn(null);
                default -> { }
            }
            tasks.remove().run();
            if (invalidation.isEmpty()) verify(spawner).drainSlotIfSimilar(eq(0), same(template), eq(amount));
            else verify(spawner, never()).drainSlotIfSimilar(anyInt(), any(), anyInt());
            verify(event).setCancelled(true);
        }
    }
}
