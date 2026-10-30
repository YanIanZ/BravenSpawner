package dev.yanianz.bravenspawner.manager;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** EX-09: the chunk cap counts spawner blocks by default and uses a per-chunk index. */
class ChunkLimitTest {
    private static Settings settings(int limit, String mode) {
        YamlConfiguration root = new YamlConfiguration();
        root.set("spawner-limit-in-chunk", limit);
        if (mode != null) root.set("spawner-limit-in-chunk-mode", mode);
        return Settings.load(root, null, null);
    }

    private static Spawner spawner(int x, int stack) {
        return new Spawner(UUID.randomUUID(), UUID.randomUUID(), "world", x, 64, 0,
                Spawner.Kind.MOB, "ZOMBIE", stack, 0, true);
    }

    @Test void blockModeCountsBlocksAndIgnoresStackUnits() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            SpawnerManager manager = new SpawnerManager(mock(BravenSpawner.class), settings(2, null));
            Spawner big = spawner(1, 5_000);
            manager.register(big);
            manager.register(spawner(17, 1)); // next chunk
            assertEquals(1, manager.spawnersInChunk(world, 0, 0));
            assertEquals(5_000, manager.stackedInChunk(world, 0, 0));
            assertTrue(manager.chunkAllowsNewSpawner(world, 0, 0, 10_000));
            assertEquals(Integer.MAX_VALUE, manager.chunkUnitRoom(world, 0, 0));
            manager.register(spawner(2, 1));
            assertFalse(manager.chunkAllowsNewSpawner(world, 0, 0, 1));
            manager.unregister(big);
            assertEquals(1, manager.spawnersInChunk(world, 0, 0));
            assertTrue(manager.chunkAllowsNewSpawner(world, 0, 0, 1));
        }
    }

    @Test void stacksModeCountsUnits() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            SpawnerManager manager = new SpawnerManager(mock(BravenSpawner.class), settings(100, "stacks"));
            manager.register(spawner(1, 60));
            assertEquals(40, manager.chunkUnitRoom(world, 0, 0));
            assertTrue(manager.chunkAllowsNewSpawner(world, 0, 0, 40));
            assertFalse(manager.chunkAllowsNewSpawner(world, 0, 0, 41));
        }
    }
}
