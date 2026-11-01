package dev.yanianz.bravenspawner.sell;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.econ.Economy;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.model.StockEntry;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SellMultiplierTest {
    @TempDir Path directory;
    BravenSpawner plugin;
    Economy economy;
    Player player;
    Spawner spawner;
    SellService service;
    YamlConfiguration config;

    @BeforeEach void setup() {
        plugin = mock(BravenSpawner.class);
        economy = mock(Economy.class);
        player = mock(Player.class);
        spawner = mock(Spawner.class);
        config = new YamlConfiguration();
        config.set("sell-prices.STONE", 2.0);
        config.set("economy.sell-multiplier", 2.0);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.economy()).thenReturn(economy);
        when(economy.enabled()).thenReturn(true);
        when(spawner.id()).thenReturn(UUID.randomUUID());
        ItemStack stack = mock(ItemStack.class);
        Material material = mock(Material.class);
        when(material.name()).thenReturn("STONE");
        when(material.isAir()).thenReturn(false);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(4);
        when(stack.clone()).thenReturn(stack);
        when(stack.isSimilar(stack)).thenReturn(true);
        StockEntry entry4 = new StockEntry(stack, 4);
        when(spawner.stock()).thenReturn(List.of(entry4));
        when(spawner.take(stack, 4L)).thenReturn(4L);
        service = SellService.install(plugin);
        EscrowFixtures.escrowable(spawner);
        EscrowFixtures.inline(service);
        EscrowFixtures.live(plugin, spawner, player);
    }

    @Test void combinesWandAndServerMultipliersIntoOnePayment() {
        when(economy.deposit(player, 24.0)).thenReturn(true);
        SellService.Sale sale = service.sell(player, spawner, 1.5);
        assertEquals(SellService.Status.SOLD, sale.status());
        assertEquals(24.0, sale.value());
        verify(economy, times(1)).deposit(player, 24.0);
        verify(economy, times(1)).deposit(eq(player), anyDouble());
        verify(spawner).release(any());
    }

    @Test void payoutIsRoundedHalfUpToConfiguredDecimals() {
        config.set("economy.sell-multiplier", 1.0);
        config.set("sell-prices.STONE", 0.33375);
        when(economy.deposit(player, 1.34)).thenReturn(true);
        SellService.Sale sale = service.sell(player, spawner);
        assertEquals(SellService.Status.SOLD, sale.status());
        assertEquals(1.34, sale.value());
        config.set("economy.round-to", 0);
        assertEquals(1.0, service.value(spawner));
    }

    @Test void moneyTextUsesTheFormatsSection() {
        config.set("money-decimals.formats.1", "%number%k$");
        assertEquals("1.50k$", service.format(1500));
    }

    @Test void rejectedMultipliedPaymentLeavesStorageUntouched() {
        SellService.Sale sale = service.sell(player, spawner, 1.5);
        assertEquals(SellService.Status.FAILED, sale.status());
        verify(spawner, never()).release(any());
        verify(spawner).refund(any());
    }

    @Test void nonFiniteMultiplierCannotReachEconomy() {
        SellService.Sale sale = service.sell(player, spawner, Double.POSITIVE_INFINITY);
        assertEquals(SellService.Status.FAILED, sale.status());
        verify(economy, never()).deposit(any(), anyDouble());
        verify(spawner, never()).release(any());
    }
    @Test void fullCollectionGateRefusesPaymentBeforeStorageIsFull() {
        var product = mock(dev.yanianz.bravenspawner.product.ProductDefinition.class);
        when(product.mustBeFullToCollect()).thenReturn(true);
        when(plugin.product(spawner)).thenReturn(product);
        when(plugin.maximumItems(spawner)).thenReturn(8L);
        when(spawner.storedCount()).thenReturn(7L);
        SellService.Sale sale = service.sell(player, spawner);
        assertEquals(SellService.Status.NOTHING_TO_SELL, sale.status());
        verify(economy, never()).deposit(any(), anyDouble());
        verify(spawner, never()).release(any());
    }

    @Test void fullCollectionGateAllowsExactCapacity() {
        var product = mock(dev.yanianz.bravenspawner.product.ProductDefinition.class);
        when(product.mustBeFullToCollect()).thenReturn(true);
        when(plugin.product(spawner)).thenReturn(product);
        when(plugin.maximumItems(spawner)).thenReturn(8L);
        when(spawner.storedCount()).thenReturn(8L);
        when(economy.deposit(player, 16.0)).thenReturn(true);
        assertEquals(SellService.Status.SOLD, service.sell(player, spawner).status());
    }

    @Test void unlimitedCapacityDoesNotBlockCollection() {
        var product = mock(dev.yanianz.bravenspawner.product.ProductDefinition.class);
        when(product.mustBeFullToCollect()).thenReturn(true);
        when(plugin.product(spawner)).thenReturn(product);
        when(plugin.maximumItems(spawner)).thenReturn(0L);
        when(spawner.storedCount()).thenReturn(4L);
        when(economy.deposit(player, 16.0)).thenReturn(true);
        assertEquals(SellService.Status.SOLD, service.sell(player, spawner).status());
    }

    @Test void productDropPriceOverridesGlobalQuoteAndSettlement() {
        var product = mock(dev.yanianz.bravenspawner.product.ProductDefinition.class);
        var drop = mock(dev.yanianz.bravenspawner.product.ProductDefinition.Drop.class);
        Material dropMaterial = spawner.stock().getFirst().template().getType();
        when(drop.material()).thenReturn(dropMaterial);
        when(drop.sellPrice()).thenReturn(3.0);
        when(product.drops()).thenReturn(List.of(drop));
        when(plugin.product(spawner)).thenReturn(product);
        when(economy.deposit(player, 24.0)).thenReturn(true);
        assertEquals(24.0, service.value(spawner));
        assertEquals(4, service.sellableItems(spawner));
        SellService.Sale sale = service.sell(player, spawner);
        assertEquals(SellService.Status.SOLD, sale.status());
        assertEquals(24.0, sale.value());
    }

    @Test void explicitZeroProductPriceSuppressesGlobalPrice() {
        var product = mock(dev.yanianz.bravenspawner.product.ProductDefinition.class);
        var drop = mock(dev.yanianz.bravenspawner.product.ProductDefinition.Drop.class);
        Material dropMaterial = spawner.stock().getFirst().template().getType();
        when(drop.material()).thenReturn(dropMaterial);
        when(drop.sellPrice()).thenReturn(0.0);
        when(product.drops()).thenReturn(List.of(drop));
        when(plugin.product(spawner)).thenReturn(product);
        assertEquals(0.0, service.value(spawner));
        assertEquals(0, service.sellableItems(spawner));
        assertEquals(SellService.Status.NOTHING_TO_SELL, service.sell(player, spawner).status());
        verify(economy, never()).deposit(any(), anyDouble());
    }

    @Test void recordsSuccessfulSettlementExactlyOnceWithPlayerActor() {
        when(economy.deposit(player, 16.0)).thenReturn(true);
        assertEquals(SellService.Status.SOLD, service.sell(player, spawner).status());
        verify(plugin, times(1)).record(eq(spawner), eq(dev.yanianz.bravenspawner.audit.AuditEvent.Action.SELL),
                eq(player), eq(4L), anyString());
    }

    /** VSS-05: the economy is only asked after the units left storage into a saved escrow. */
    @Test void paymentHappensAfterEscrowAndWithoutTheSpawnerMonitor() throws Exception {
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            when(economy.deposit(player, 16.0)).thenAnswer(invocation -> {
                verify(spawner).hold(any(), any(), any(), eq(16.0), anyList());
                verify(spawner, never()).release(any());
                // A slow provider must not block production or withdrawals.
                var entered = worker.submit(() -> { synchronized (spawner) { return true; } });
                assertTrue(entered.get(2, java.util.concurrent.TimeUnit.SECONDS));
                return true;
            });
            assertEquals(SellService.Status.SOLD, service.sell(player, spawner).status());
            verify(spawner).release(any());
            verify(spawner, never()).refund(any());
        } finally { worker.shutdownNow(); }
    }

    @Test void failedSaveReturnsTheEscrowWithoutPaying() {
        service.testHooks(null, () -> java.util.concurrent.CompletableFuture.completedFuture(false));
        SellService.Sale sale = service.sell(player, spawner);
        assertEquals(SellService.Status.FAILED, sale.status());
        verify(economy, never()).deposit(any(), anyDouble());
        verify(spawner).refund(any());
        verify(spawner, never()).release(any());
    }

    @Test void settlementOutcomeArrivesOnceAfterTheSave() {
        var save = new java.util.concurrent.CompletableFuture<Boolean>();
        service.testHooks(null, () -> save);
        when(economy.deposit(player, 16.0)).thenReturn(true);
        List<SellService.Sale> outcomes = new java.util.ArrayList<>();
        SellService.Sale sale = service.sell(player, spawner, 1.0, outcomes::add);
        assertEquals(SellService.Status.SETTLING, sale.status());
        assertEquals(4, sale.items());
        assertTrue(service.settling(spawner));
        verify(economy, never()).deposit(any(), anyDouble());
        save.complete(true);
        assertEquals(1, outcomes.size());
        assertEquals(SellService.Status.SOLD, outcomes.getFirst().status());
        assertFalse(service.settling(spawner));
        verify(economy, times(1)).deposit(player, 16.0);
        verify(plugin, times(1)).record(eq(spawner), eq(dev.yanianz.bravenspawner.audit.AuditEvent.Action.SELL),
                eq(player), eq(4L), anyString());
    }

    @Test void shutdownReturnsSalesStillWaitingForTheirSave() {
        var save = new java.util.concurrent.CompletableFuture<Boolean>();
        service.testHooks(null, () -> save);
        List<SellService.Sale> outcomes = new java.util.ArrayList<>();
        assertEquals(SellService.Status.SETTLING, service.sell(player, spawner, 1.0, outcomes::add).status());
        service.shutdown();
        verify(spawner).refund(any());
        save.complete(true);
        verify(economy, never()).deposit(any(), anyDouble());
        verify(spawner, never()).release(any());
        assertTrue(outcomes.isEmpty(), "a sale returned at shutdown reports nothing afterwards");
    }

    @Test void retiredPlayerBeforePaymentReturnsTheEscrow() {
        service.testHooks(new SellService.Dispatcher() {
            @Override public boolean onPlayer(org.bukkit.entity.Player p, Runnable task, Runnable retired) {
                retired.run();
                return true;
            }
            @Override public boolean onGlobal(Runnable task) { task.run(); return true; }
        }, null);
        assertEquals(SellService.Status.FAILED, service.sell(player, spawner).status());
        verify(economy, never()).deposit(any(), anyDouble());
        verify(spawner).refund(any());
    }

    @Test void escrowCapRefusesANewSale() {
        doReturn(null).when(spawner).hold(any(), any(), any(), anyDouble(), anyList());
        assertEquals(SellService.Status.PENDING, service.sell(player, spawner).status());
        verify(economy, never()).deposit(any(), anyDouble());
    }

    @Test void unresolvedListsOnlyEscrowsNoLiveSaleOwns() {
        var escrow = new dev.yanianz.bravenspawner.model.Escrow(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "VAULT", 5.0, 1L, List.of(spawner.stock().getFirst()));
        when(spawner.escrows()).thenReturn(List.of(escrow));
        when(plugin.manager().all()).thenReturn(List.of(spawner));
        assertEquals(1, service.unresolved().size());
        assertSame(escrow, service.unresolved().getFirst().getValue());
    }

    @Test void removedStockIsMarkedDirtyWithoutRequiringGuiOrProduction() {
        when(economy.deposit(player, 16.0)).thenReturn(true);
        assertEquals(SellService.Status.SOLD, service.sell(player, spawner).status());
        verify(plugin, atLeastOnce()).markDirty(spawner);
    }

}
