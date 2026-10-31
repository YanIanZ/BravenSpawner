package dev.yanianz.bravenspawner.sell;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.commerce.ShopBridge;
import dev.yanianz.bravenspawner.econ.Currency;
import dev.yanianz.bravenspawner.econ.CurrencyFormats;
import dev.yanianz.bravenspawner.econ.CurrencyRegistry;
import dev.yanianz.bravenspawner.econ.currency.ExpCurrency;
import dev.yanianz.bravenspawner.econ.currency.ItemCurrency;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Schedulers;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.List;
import java.util.HashMap;
import java.util.Queue;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutosellSchedulingTest {
    record Task(Runnable run, Runnable retired) { }
    final Queue<Task> queue = new ArrayDeque<>();
    final UUID ownerId = UUID.randomUUID();
    final UUID spawnerId = UUID.randomUUID();
    final BravenSpawner plugin = mock(BravenSpawner.class);
    final SpawnerManager manager = mock(SpawnerManager.class);
    final Spawner spawner = mock(Spawner.class);
    final Player owner = mock(Player.class);
    final OfflinePlayer offline = mock(OfflinePlayer.class);
    final EntityScheduler scheduler = mock(EntityScheduler.class);
    final Currency currency = mock(Currency.class);
    final ShopBridge shops = mock(ShopBridge.class);
    final ItemStack stock = mock(ItemStack.class);
    final YamlConfiguration config = new YamlConfiguration();
    MockedStatic<Bukkit> bukkit;
    MockedStatic<Schedulers> schedulers;
    MockedConstruction<ShopHook> hooks;
    CurrencyRegistry registry;
    Consumer<ScheduledTask> pass;
    SellService service;
    boolean entityContext;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        schedulers = mockStatic(Schedulers.class);
        hooks = mockConstruction(ShopHook.class);
        schedulers.when(Schedulers::isFolia).thenReturn(true);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
        when(global.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong())).thenAnswer(call -> {
            pass = call.getArgument(1);
            return mock(ScheduledTask.class);
        });
        bukkit.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(owner);
        bukkit.when(() -> Bukkit.getOfflinePlayer(ownerId)).thenReturn(offline);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(owner)).thenAnswer(call -> entityContext);
        when(owner.getUniqueId()).thenReturn(ownerId);
        when(owner.isOnline()).thenReturn(true);
        when(owner.getScheduler()).thenReturn(scheduler);
        when(offline.getUniqueId()).thenReturn(ownerId);
        when(scheduler.execute(eq(plugin), any(), any(), eq(1L))).thenAnswer(call -> {
            queue.add(new Task(call.getArgument(1), call.getArgument(2)));
            return true;
        });
        config.set("economy.autosell", true);
        config.set("economy.enabled", true);
        config.set("economy.currencies.sell", "TEST");
        config.set("sell-prices.STONE", 2.0);
        config.set("disable-autosell-notifications", true);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.ready()).thenReturn(true);
        when(plugin.manager()).thenReturn(manager);
        when(manager.all()).thenReturn(List.of(spawner));
        when(manager.byId(spawnerId)).thenReturn(spawner);
        when(spawner.id()).thenReturn(spawnerId);
        when(spawner.owner()).thenReturn(ownerId);
        when(spawner.contents()).thenReturn(List.of(stock));
        Material material = mock(Material.class);
        when(material.name()).thenReturn("STONE");
        when(material.isAir()).thenReturn(false);
        when(stock.getType()).thenReturn(material);
        when(stock.getAmount()).thenReturn(4);
        when(stock.clone()).thenReturn(stock);
        when(stock.isSimilar(stock)).thenReturn(true);
        when(spawner.drainSlot(0, 4)).thenReturn(stock);
        when(currency.id()).thenReturn("TEST");
        when(currency.available()).thenReturn(true);
        when(currency.deposit(any(), anyDouble())).thenReturn(Currency.Result.ok(8));
        registry = new CurrencyRegistry(Logger.getAnonymousLogger(), new CurrencyFormats(() -> config), null);
        registry.register(currency);
        when(plugin.currencies()).thenReturn(registry);
        when(plugin.shops()).thenReturn(shops);
        when(shops.priceOf(any(), same(stock))).thenReturn(-1.0);
        service = SellService.install(plugin);
        clearInvocations(currency, shops);
    }

    @AfterEach void cleanup() {
        service.shutdown();
        hooks.close();
        schedulers.close();
        bukkit.close();
    }

    void sweep() { pass.accept(mock(ScheduledTask.class)); }
    void tick() {
        Task task = queue.remove();
        entityContext = true;
        try { task.run().run(); } finally { entityContext = false; }
    }
    void noPayment() {
        verify(currency, never()).deposit(any(), anyDouble());
        verify(spawner, never()).drainSlot(anyInt(), anyInt());
    }

    @Test void quoteAndPaymentWaitForOwnerSchedulerAndRepeatedSweepsDoNotQueueTwice() {
        when(shops.priceOf(isNull(), same(stock))).thenAnswer(call -> {
            assertTrue(entityContext, "shop quotes must run on owner context");
            return -1.0;
        });
        when(currency.deposit(same(owner), eq(8.0))).thenAnswer(call -> {
            assertTrue(entityContext, "deposit must run on owner context");
            return Currency.Result.ok(8);
        });
        sweep(); sweep();
        verifyNoInteractions(currency, shops);
        noPayment();
        assertEquals(1, queue.size());
        tick();
        verify(currency).deposit(owner, 8.0);
        verify(spawner).drainSlot(0, 4);
    }

    @Test void realExperienceCurrencyCreditsOnlyOnOwnedThread() {
        registry.register(ExpCurrency.levels(new CurrencyFormats(() -> config)));
        config.set("economy.currencies.sell", "EXP_LEVELS");
        when(owner.getLevel()).thenReturn(3);
        sweep();
        verify(owner, never()).setLevel(anyInt());
        assertEquals(1, queue.size());
        tick();
        verify(owner).setLevel(11);
        verify(spawner).drainSlot(0, 4);
    }

    @Test void realItemCurrencyTouchesInventoryOnlyOnOwnedThread() {
        registry.register(new ItemCurrency(null));
        config.set("economy.currencies.sell", "ITEM");
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(owner.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack.class))).thenAnswer(call -> {
            assertTrue(entityContext, "item payout must run on owner context");
            return new HashMap<Integer, ItemStack>();
        });
        try (var items = mockConstruction(ItemStack.class, (item, context) -> {
            when(item.getMaxStackSize()).thenReturn(64);
            when(item.clone()).thenReturn(item);
        })) {
            sweep(); verifyNoInteractions(inventory);
            tick();
            assertEquals(1, items.constructed().size());
            ItemStack payout = items.constructed().getFirst();
            verify(payout).setAmount(8);
            verify(inventory).addItem(payout);
            verify(spawner).drainSlot(0, 4);
        }
    }

    @Test void disconnectBeforeTickPreservesStock() {
        sweep(); when(owner.isOnline()).thenReturn(false); tick(); noPayment();
    }
    @Test void removedSpawnerPreservesStock() {
        sweep(); when(manager.byId(spawnerId)).thenReturn(null); tick(); noPayment();
    }
    @Test void replacedSpawnerWithSameIdRejectsOldWork() {
        sweep(); when(manager.byId(spawnerId)).thenReturn(mock(Spawner.class)); tick(); noPayment();
    }
    @Test void changedOwnerRejectsOldWork() {
        sweep(); when(spawner.owner()).thenReturn(UUID.randomUUID()); tick(); noPayment();
    }
    @Test void disabledAutosellBeforeTickRejectsOldWork() {
        sweep(); config.set("economy.autosell", false); tick(); noPayment();
    }
    @Test void disabledEconomyBeforeTickPreservesStock() {
        sweep(); config.set("economy.enabled", false); tick(); noPayment();
    }
    @Test void unavailableStorageBeforeTickPreservesStock() {
        sweep(); when(plugin.ready()).thenReturn(false); tick(); noPayment();
    }
    @Test void shutdownInvalidatesQueuedWork() {
        sweep(); service.shutdown(); tick(); noPayment();
    }
    @Test void retiredCallbackReleasesReservationWithoutPaying() {
        sweep(); queue.remove().retired().run(); noPayment();
        sweep(); assertEquals(1, queue.size()); tick(); verify(currency).deposit(owner, 8.0);
    }
    @Test void schedulingRefusalDoesNotLeakReservation() {
        when(scheduler.execute(eq(plugin), any(), any(), eq(1L))).thenReturn(false);
        sweep(); noPayment();
        when(scheduler.execute(eq(plugin), any(), any(), eq(1L))).thenAnswer(call -> {
            queue.add(new Task(call.getArgument(1), call.getArgument(2))); return true;
        });
        sweep(); assertEquals(1, queue.size()); tick(); verify(currency).deposit(owner, 8.0);
    }
    @Test void schedulingExceptionDoesNotLeakReservation() {
        when(scheduler.execute(eq(plugin), any(), any(), eq(1L))).thenThrow(new IllegalStateException("retired"));
        sweep(); noPayment();
        doAnswer(call -> { queue.add(new Task(call.getArgument(1), call.getArgument(2))); return true; })
                .when(scheduler).execute(eq(plugin), any(), any(), eq(1L));
        sweep(); assertEquals(1, queue.size()); tick(); verify(currency).deposit(owner, 8.0);
    }
    @Test void offlineFoliaNeverCallsProviders() {
        bukkit.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(null);
        sweep(); verifyNoInteractions(currency, shops); noPayment(); assertTrue(queue.isEmpty());
    }
    @Test void offlinePaperStillPaysOfflineCapableCurrency() {
        schedulers.when(Schedulers::isFolia).thenReturn(false);
        bukkit.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(null);
        sweep(); verify(currency).deposit(offline, 8.0); verify(spawner).drainSlot(0, 4);
    }
    @Test void offlinePaperNeverQuotesOnlineOnlyCurrency() {
        schedulers.when(Schedulers::isFolia).thenReturn(false);
        bukkit.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(null);
        when(currency.requiresOnline()).thenReturn(true);
        sweep(); verifyNoInteractions(shops); noPayment();
    }
    @Test void currencyChangedBeforeTickUsesCurrentSelection() {
        Currency replacement = mock(Currency.class);
        when(replacement.id()).thenReturn("OTHER");
        when(replacement.available()).thenReturn(true);
        when(replacement.deposit(any(), anyDouble())).thenReturn(Currency.Result.ok(8));
        registry.register(replacement);
        sweep(); config.set("economy.currencies.sell", "OTHER"); tick();
        verify(currency, never()).deposit(any(), anyDouble());
        verify(replacement).deposit(owner, 8.0);
    }
    @Test void playerSessionReplacementRejectsOldCallback() {
        sweep(); bukkit.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(mock(Player.class));
        tick(); noPayment();
    }
    @Test void providerExceptionKeepsStockAndAllowsNextSweep() {
        when(currency.deposit(any(), anyDouble())).thenThrow(new IllegalStateException("provider failed"));
        sweep(); tick(); verify(spawner, never()).drainSlot(anyInt(), anyInt());
        sweep(); assertEquals(1, queue.size());
    }
    @Test void pluginDisableHookInvalidatesQueuedWork() {
        sweep();
        doCallRealMethod().when(plugin).onDisable();
        plugin.onDisable();
        tick(); noPayment();
    }
    @Test void unreadyStartupDoesNotDispatchOrQueryProviders() {
        when(plugin.ready()).thenReturn(false);
        sweep(); assertTrue(queue.isEmpty()); verifyNoInteractions(currency, shops); noPayment();
    }

    @Test void rejectedPaymentReleasesQueueAndKeepsStock() {
        when(currency.deposit(any(), anyDouble())).thenReturn(Currency.Result.fail("rejected"));
        sweep(); tick(); verify(spawner, never()).drainSlot(anyInt(), anyInt());
        sweep(); assertEquals(1, queue.size());
    }
}
