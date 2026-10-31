package dev.yanianz.bravenspawner.sell;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.econ.Economy;
import dev.yanianz.bravenspawner.model.Spawner;
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
        when(spawner.contents()).thenReturn(List.of(stack));
        when(spawner.drainSlot(0, 4)).thenReturn(stack);
        service = SellService.install(plugin);
    }

    @Test void combinesWandAndServerMultipliersIntoOnePayment() {
        when(economy.deposit(player, 24.0)).thenReturn(true);
        SellService.Sale sale = service.sell(player, spawner, 1.5);
        assertEquals(SellService.Status.SOLD, sale.status());
        assertEquals(24.0, sale.value());
        verify(economy, times(1)).deposit(player, 24.0);
        verify(economy, times(1)).deposit(eq(player), anyDouble());
        verify(spawner).drainSlot(0, 4);
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
        verify(spawner, never()).drainSlot(anyInt(), anyInt());
    }

    @Test void nonFiniteMultiplierCannotReachEconomy() {
        SellService.Sale sale = service.sell(player, spawner, Double.POSITIVE_INFINITY);
        assertEquals(SellService.Status.FAILED, sale.status());
        verify(economy, never()).deposit(any(), anyDouble());
        verify(spawner, never()).drainSlot(anyInt(), anyInt());
    }
    @Test void fullCollectionGateRefusesPaymentBeforeStorageIsFull() {
        var product = mock(dev.yanianz.bravenspawner.product.ProductDefinition.class);
        when(product.mustBeFullToCollect()).thenReturn(true);
        when(plugin.product(spawner)).thenReturn(product);
        when(plugin.maximumItems(spawner)).thenReturn(8);
        when(spawner.storedCount()).thenReturn(7);
        SellService.Sale sale = service.sell(player, spawner);
        assertEquals(SellService.Status.NOTHING_TO_SELL, sale.status());
        verify(economy, never()).deposit(any(), anyDouble());
        verify(spawner, never()).drainSlot(anyInt(), anyInt());
    }

    @Test void fullCollectionGateAllowsExactCapacity() {
        var product = mock(dev.yanianz.bravenspawner.product.ProductDefinition.class);
        when(product.mustBeFullToCollect()).thenReturn(true);
        when(plugin.product(spawner)).thenReturn(product);
        when(plugin.maximumItems(spawner)).thenReturn(8);
        when(spawner.storedCount()).thenReturn(8);
        when(economy.deposit(player, 16.0)).thenReturn(true);
        assertEquals(SellService.Status.SOLD, service.sell(player, spawner).status());
    }

    @Test void unlimitedCapacityDoesNotBlockCollection() {
        var product = mock(dev.yanianz.bravenspawner.product.ProductDefinition.class);
        when(product.mustBeFullToCollect()).thenReturn(true);
        when(plugin.product(spawner)).thenReturn(product);
        when(plugin.maximumItems(spawner)).thenReturn(0);
        when(spawner.storedCount()).thenReturn(4);
        when(economy.deposit(player, 16.0)).thenReturn(true);
        assertEquals(SellService.Status.SOLD, service.sell(player, spawner).status());
    }

    @Test void productDropPriceOverridesGlobalQuoteAndSettlement() {
        var product = mock(dev.yanianz.bravenspawner.product.ProductDefinition.class);
        var drop = mock(dev.yanianz.bravenspawner.product.ProductDefinition.Drop.class);
        Material dropMaterial = spawner.contents().getFirst().getType();
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
        Material dropMaterial = spawner.contents().getFirst().getType();
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

    @Test void recordsPendingSettlementOnlyAfterReconciliationWithoutPayingAgain() {
        ItemStack stock = spawner.contents().getFirst();
        when(spawner.contents()).thenReturn(List.of(stock)).thenReturn(List.of(stock)).thenReturn(List.of(stock)).thenReturn(List.of());
        when(spawner.drainSlot(0, 4)).thenReturn(null, stock);
        when(economy.deposit(player, 16.0)).thenReturn(true);
        assertEquals(SellService.Status.PENDING, service.sell(player, spawner).status());
        verify(plugin, never()).record(any(), any(), any(), anyLong(), anyString());
        service.sell(player, spawner);
        service.sell(player, spawner);
        verify(economy, times(1)).deposit(eq(player), anyDouble());
        verify(plugin, times(1)).record(eq(spawner), eq(dev.yanianz.bravenspawner.audit.AuditEvent.Action.SELL),
                eq(player), eq(4L), anyString());
    }

    @Test void settlementHoldsSpawnerMonitorWhilePaying() throws Exception {
        java.util.concurrent.CountDownLatch attempted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            when(economy.deposit(player, 16.0)).thenAnswer(invocation -> {
                worker.submit(() -> {
                    attempted.countDown();
                    synchronized (spawner) { entered.countDown(); }
                });
                assertTrue(attempted.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(entered.await(100, java.util.concurrent.TimeUnit.MILLISECONDS),
                        "a withdrawal must wait until quote/payment/drain has completed");
                return true;
            });
            assertEquals(SellService.Status.SOLD, service.sell(player, spawner).status());
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); }
    }

    @Test void removedStockIsMarkedDirtyWithoutRequiringGuiOrProduction() {
        when(economy.deposit(player, 16.0)).thenReturn(true);
        assertEquals(SellService.Status.SOLD, service.sell(player, spawner).status());
        verify(plugin, atLeastOnce()).markDirty(spawner);
    }

}
