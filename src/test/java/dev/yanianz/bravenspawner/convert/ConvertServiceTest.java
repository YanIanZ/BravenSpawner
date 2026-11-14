package dev.yanianz.bravenspawner.convert;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Block/item mob resolution, same-chunk cap accounting, owner attribution and
 * retry bounds.
 */
class ConvertServiceTest {

    private static String lookup(String mob) {
        return "ZOMBIE".equals(mob) ? mob : null;
    }

    // ------------------------------------------------------- block products

    private static Block spawnerBlock(EntityType type) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.SPAWNER);
        CreatureSpawner spawner = mock(CreatureSpawner.class);
        when(spawner.getSpawnedType()).thenReturn(type);
        when(block.getState()).thenReturn(spawner);
        return block;
    }

    @Test void blockWithProductResolves() {
        assertEquals("ZOMBIE", ConvertService.productForBlock(spawnerBlock(EntityType.ZOMBIE), ConvertServiceTest::lookup));
    }

    @Test void blockWithoutProductStaysNull() {
        assertNull(ConvertService.productForBlock(spawnerBlock(EntityType.CREEPER), ConvertServiceTest::lookup));
        assertNull(ConvertService.productForBlock(spawnerBlock(null), ConvertServiceTest::lookup));
    }

    @Test void nonSpawnerBlockStaysNull() {
        Block chest = mock(Block.class);
        when(chest.getType()).thenReturn(Material.CHEST);
        assertNull(ConvertService.productForBlock(chest, ConvertServiceTest::lookup));
        Block odd = mock(Block.class);
        when(odd.getType()).thenReturn(Material.SPAWNER);
        when(odd.getState()).thenReturn(mock(BlockState.class));
        assertNull(ConvertService.productForBlock(odd, ConvertServiceTest::lookup));
        assertNull(ConvertService.productForBlock(null, ConvertServiceTest::lookup));
        assertNull(ConvertService.productForBlock(spawnerBlock(EntityType.ZOMBIE), null));
    }

    // -------------------------------------------------------- item mobs

    private static ItemStack itemWithBlockState(EntityType type) {
        ItemStack item = mock(ItemStack.class);
        BlockStateMeta meta = mock(BlockStateMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.getKeys()).thenReturn(Set.of());
        when(meta.hasBlockState()).thenReturn(true);
        CreatureSpawner spawner = mock(CreatureSpawner.class);
        when(spawner.getSpawnedType()).thenReturn(type);
        when(meta.getBlockState()).thenReturn(spawner);
        return item;
    }

    @Test void itemBlockStateYieldsMob() {
        assertEquals("ZOMBIE", ConvertService.mobForItem(itemWithBlockState(EntityType.ZOMBIE)).orElseThrow());
        assertTrue(ConvertService.mobForItem(itemWithBlockState(null)).isEmpty());
    }

    @Test void plainItemWithNoMobInfoYieldsEmpty() {
        ItemStack plain = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(plain.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.getKeys()).thenReturn(Set.of());
        assertTrue(ConvertService.mobForItem(plain).isEmpty());
        assertTrue(ConvertService.mobForItem(null).isEmpty());
    }

    // ------------------------------------------- same-chunk cap accounting

    private static Settings settings(int limit, String mode) {
        YamlConfiguration root = new YamlConfiguration();
        root.set("spawner-limit-in-chunk", limit);
        if (mode != null) {
            root.set("spawner-limit-in-chunk-mode", mode);
        }
        return Settings.load(root, null, null);
    }

    private static Spawner spawner(int x, int stack) {
        return new Spawner(UUID.randomUUID(), UUID.randomUUID(), "world", x, 64, 0,
                Spawner.Kind.MOB, "ZOMBIE", stack, 0, true);
    }

    @Test void pendingConversionsCountAgainstTheBlockCap() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            SpawnerManager manager = new SpawnerManager(mock(BravenSpawner.class), settings(2, null));
            manager.register(spawner(1, 1));
            assertTrue(ConvertService.chunkAllowsWithPending(manager, world, 0, 0, 0));
            assertFalse(ConvertService.chunkAllowsWithPending(manager, world, 0, 0, 1),
                    "one managed block plus one pending conversion fills a cap of 2");
        }
    }

    @Test void pendingConversionsCountAgainstTheStacksCap() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            SpawnerManager manager = new SpawnerManager(mock(BravenSpawner.class), settings(3, "stacks"));
            manager.register(spawner(1, 2));
            assertTrue(ConvertService.chunkAllowsWithPending(manager, world, 0, 0, 0));
            assertFalse(ConvertService.chunkAllowsWithPending(manager, world, 0, 0, 1),
                    "two units plus one pending unit plus the new one exceed a cap of 3");
        }
    }

    @Test void zeroLimitMeansNoCap() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            SpawnerManager manager = new SpawnerManager(mock(BravenSpawner.class), settings(0, null));
            assertTrue(ConvertService.chunkAllowsWithPending(manager, world, 0, 0, 999));
            assertFalse(ConvertService.chunkAllowsWithPending(null, world, 0, 0, 0));
        }
    }

    // ------------------------------------------- owner attribution + retry

    @Test void conversionsKeepAPlacerButNeverInventOne() {
        UUID placer = UUID.randomUUID();
        assertEquals(placer, ConvertService.ownerFor(placer));
        assertEquals(new UUID(0L, 0L), ConvertService.ownerFor(null));
        assertNotEquals(placer, ConvertService.ownerFor(null));
        assertNotNull(ConvertService.SYSTEM_OWNER);
    }

    @Test void retriesAreBoundedAndThrottleOnly() {
        assertTrue(ConvertService.shouldRetry(1, 0));
        assertTrue(ConvertService.shouldRetry(5, ConvertService.MAX_RETRIES - 1));
        assertFalse(ConvertService.shouldRetry(5, ConvertService.MAX_RETRIES));
        assertFalse(ConvertService.shouldRetry(0, 0), "nothing throttled means nothing to retry");
    }
}
