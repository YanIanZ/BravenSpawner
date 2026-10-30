package dev.yanianz.bravenspawner.commerce;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopBridgeTest {
    private static final Logger LOGGER = Logger.getAnonymousLogger();

    private Material material() {
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(material.name()).thenReturn("STONE");
        return material;
    }

    private ShopAdapter adapter(String name, double price) {
        ShopAdapter adapter = mock(ShopAdapter.class);
        when(adapter.name()).thenReturn(name);
        when(adapter.available()).thenReturn(true);
        when(adapter.priceOf(any(), any())).thenReturn(price);
        return adapter;
    }

    @Test void configuredSelectionDoesNotFallThroughToOtherInstalledShops() {
        ShopAdapter essentials = adapter("EssentialsX", 5.0);
        ShopAdapter shop = adapter("ShopGUI+", 20.0);
        ShopBridge bridge = new ShopBridge(LOGGER, List.of(shop, essentials), "Essentials");
        assertEquals(List.of(essentials), bridge.adapters());
        assertSame(essentials, bridge.active().orElseThrow());
        verify(shop, never()).available();
    }

    @Test void noneAndUnknownSelectionsLeaveProviderPricingDisabled() {
        ShopAdapter shop = adapter("ShopGUI+", 20.0);
        assertTrue(new ShopBridge(LOGGER, List.of(shop), "None").adapters().isEmpty());
        assertTrue(new ShopBridge(LOGGER, List.of(shop), "unknown").adapters().isEmpty());
        assertSame(shop, new ShopBridge(LOGGER, List.of(shop), "ShopGUIPlus").active().orElseThrow());
    }

    @Test void providerReceivesOneUnitAndPlayerWithoutChangingVirtualCount() {
        ShopAdapter shop = adapter("ShopGUI+", 2.5);
        ItemStack virtual = mock(ItemStack.class);
        ItemStack unit = mock(ItemStack.class);
        Material material = material();
        when(virtual.getType()).thenReturn(material);
        when(virtual.getAmount()).thenReturn(200_000);
        when(virtual.clone()).thenReturn(unit);
        Player player = mock(Player.class);
        ShopBridge bridge = new ShopBridge(LOGGER, List.of(shop), "ShopGUI+");

        assertEquals(2.5, bridge.priceOf(player, virtual));
        verify(unit).setAmount(1);
        verify(shop).priceOf(player, unit);
        verify(virtual, never()).setAmount(anyInt());
    }

    @Test void nonfiniteAndNonpositiveProviderQuotesAreNotPrices() {
        ItemStack virtual = mock(ItemStack.class);
        Material material = material();
        when(virtual.getType()).thenReturn(material);
        when(virtual.getAmount()).thenReturn(1);
        when(virtual.clone()).thenReturn(mock(ItemStack.class));
        for (double price : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0}) {
            ShopBridge bridge = new ShopBridge(LOGGER, List.of(adapter("EssentialsX", price)), "EssentialsX");
            assertEquals(-1.0, bridge.priceOf(null, virtual));
        }
    }
}
