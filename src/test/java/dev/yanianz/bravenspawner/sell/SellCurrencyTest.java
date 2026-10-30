package dev.yanianz.bravenspawner.sell;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.econ.CurrencyFormats;
import dev.yanianz.bravenspawner.econ.CurrencyRegistry;
import dev.yanianz.bravenspawner.econ.Economy;
import dev.yanianz.bravenspawner.econ.FakeCurrency;
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

/** Settlement through a configured non-Vault currency. */
class SellCurrencyTest {
    @TempDir Path directory;
    BravenSpawner plugin;
    Economy economy;
    Player player;
    Spawner spawner;
    SellService service;
    YamlConfiguration config;
    FakeCurrency points;

    @BeforeEach void setup() {
        plugin = mock(BravenSpawner.class);
        economy = mock(Economy.class);
        player = mock(Player.class);
        spawner = mock(Spawner.class);
        config = new YamlConfiguration();
        config.set("sell-prices.STONE", 2.6);
        config.set("economy.enabled", true);
        config.set("economy.currencies.sell", "fake_points");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.economy()).thenReturn(economy);

        points = new FakeCurrency("FAKE_POINTS");
        CurrencyRegistry registry = new CurrencyRegistry(Logger.getAnonymousLogger(),
                new CurrencyFormats(() -> config), null);
        registry.register(points);
        when(plugin.currencies()).thenReturn(registry);

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

    @Test void paysTheConfiguredCurrencyNotVault() {
        SellService.Sale sale = service.sell(player, spawner);
        assertEquals(SellService.Status.SOLD, sale.status());
        assertEquals(List.of(10.4), points.deposits);
        verify(economy, never()).deposit(any(), anyDouble());
        verify(spawner).drainSlot(0, 4);
        assertSame(points, service.currency());
        assertEquals("F10.4", service.format(10.4));
    }

    @Test void rejectedDepositKeepsStockAndReportsTheProviderReason() {
        points.acceptDeposits = false;
        SellService.Sale sale = service.sell(player, spawner);
        assertEquals(SellService.Status.FAILED, sale.status());
        assertTrue(sale.reason().contains("fake rejected"));
        verify(spawner, never()).drainSlot(anyInt(), anyInt());
        verify(plugin, never()).record(any(), any(), any(), anyLong(), anyString());
    }

    @Test void unavailableCurrencyRefusesWithoutTouchingStock() {
        points.available = false;
        SellService.Sale sale = service.sell(player, spawner);
        assertEquals(SellService.Status.NO_ECONOMY, sale.status());
        assertEquals("fake is offline", sale.reason());
        assertTrue(points.deposits.isEmpty());
        verify(spawner, never()).drainSlot(anyInt(), anyInt());
    }

    @Test void wholeUnitCurrencyPaysTheRoundedDownAmount() {
        points.whole = true;
        assertEquals(10.0, service.value(spawner));
        assertEquals(SellService.Status.SOLD, service.sell(player, spawner).status());
        assertEquals(List.of(10.0), points.deposits);
    }

    @Test void saleWorthLessThanOneWholeUnitIsNotGivenAway() {
        points.whole = true;
        config.set("sell-prices.STONE", 0.2);
        SellService.Sale sale = service.sell(player, spawner);
        assertEquals(SellService.Status.NOTHING_TO_SELL, sale.status());
        assertTrue(points.deposits.isEmpty());
        verify(spawner, never()).drainSlot(anyInt(), anyInt());
    }

    @Test void disabledMasterSwitchRefusesTheSale() {
        config.set("economy.enabled", false);
        assertEquals(SellService.Status.NO_ECONOMY, service.sell(player, spawner).status());
        assertTrue(points.deposits.isEmpty());
    }
}
