package dev.yanianz.bravenspawner.item;

import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomItemsTest {

    @Test void parsesKnownPrefixesOnly() {
        assertNull(CustomItemRef.parse("DIAMOND"));
        assertNull(CustomItemRef.parse("minecraft:diamond"), "vanilla namespaced keys stay materials");
        assertEquals(new CustomItemRef("mmoitems", "SWORD:CUTLASS"), CustomItemRef.parse("MMOItems:sword:cutlass"));
        assertEquals(new CustomItemRef("ecoitems", "ruby_gem"), CustomItemRef.parse("ecoitems:ruby_gem"));
        assertEquals(new CustomItemRef("itemsadder", "myitems:Ruby"), CustomItemRef.parse("ia:myitems:Ruby"));
        assertEquals(new CustomItemRef("oraxen", "amethyst"), CustomItemRef.parse(" oraxen : amethyst "));
        assertEquals(new CustomItemRef("nexo", "onyx"), CustomItemRef.parse("nexo:onyx"));
        assertEquals(new CustomItemRef("mythicmobs", "KingsCrown"), CustomItemRef.parse("mythic:KingsCrown"));
        assertEquals("mmoitems:SWORD:CUTLASS", CustomItemRef.parse("mmoitems:sword:cutlass").toString());
    }

    @Test void rejectsUnusableKeysForKnownPrefixes() {
        assertThrows(IllegalArgumentException.class, () -> CustomItemRef.parse("mmoitems:SWORD"));
        assertThrows(IllegalArgumentException.class, () -> CustomItemRef.parse("mmoitems:SWORD:A:B"));
        assertThrows(IllegalArgumentException.class, () -> CustomItemRef.parse("nexo:"));
    }

    @Test void missingPluginProducesNothingAndWarnsOnce() {
        List<LogRecord> log = new ArrayList<>();
        CustomItems items = new CustomItems(plugin(log, null));
        CustomItemRef ref = CustomItemRef.parse("mmoitems:SWORD:CUTLASS");

        assertNull(items.create(ref, "zombie"));
        assertNull(items.create(ref, "zombie"));
        ItemStack stack = mock(ItemStack.class, RETURNS_DEEP_STUBS);
        assertFalse(items.matches(ref, stack));
        assertEquals(1, log.size(), "warned once: " + log);
        assertTrue(log.get(0).getMessage().contains("MMOItems is not enabled"));
        assertTrue(items.status().contains("MMOItems: not installed"));
    }

    @Test void incompatiblePluginFailsClosed() {
        List<LogRecord> log = new ArrayList<>();
        Plugin installed = mock(Plugin.class, RETURNS_DEEP_STUBS);
        when(installed.isEnabled()).thenReturn(true);
        when(installed.getPluginMeta().getVersion()).thenReturn("0.1");
        // The mock's class loader has no MMOItems classes, so binding must fail.
        CustomItems items = new CustomItems(plugin(log, installed));
        CustomItemRef ref = CustomItemRef.parse("mmoitems:SWORD:CUTLASS");

        assertNull(items.create(ref, "zombie"));
        assertNull(items.create(ref, "zombie"));
        assertEquals(1, log.size(), "warned once: " + log);
        assertTrue(log.get(0).getMessage().contains("not compatible"));
        assertTrue(items.status().stream().anyMatch(s -> s.startsWith("MMOItems: incompatible")));
    }

    private static Plugin plugin(List<LogRecord> log, Plugin mmoItems) {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        when(manager.getPlugin("MMOItems")).thenReturn(mmoItems);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { log.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        when(plugin.getLogger()).thenReturn(logger);
        return plugin;
    }
}
