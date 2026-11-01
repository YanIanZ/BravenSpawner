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

    /** REV-06: nearChunk returns the same set through both lookup strategies. */
    @Test void nearChunkMatchesBothStrategiesAndIgnoresOtherWorlds() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            SpawnerManager manager = new SpawnerManager(mock(BravenSpawner.class), settings(0, null));
            Spawner origin = spawner(0, 1);          // chunk 0
            Spawner edge = spawner(16 * 3 + 5, 1);   // chunk 3
            Spawner far = spawner(16 * 9, 1);        // chunk 9
            Spawner nether = new Spawner(UUID.randomUUID(), UUID.randomUUID(), "nether", 0, 64, 0,
                    Spawner.Kind.MOB, "ZOMBIE", 1, 0, true);
            for (Spawner s : new Spawner[] {origin, edge, far, nether}) manager.register(s);

            // 3 world spawners < 7x7 square: filtered world walk.
            assertEquals(java.util.Set.of(origin, edge), java.util.Set.copyOf(manager.nearChunk("world", 0, 0, 3)));
            // 3 world spawners >= 1x1 square: per-chunk lookup.
            assertEquals(java.util.List.of(origin), manager.nearChunk("world", 0, 0, 0));
            assertEquals(java.util.List.of(far), manager.nearChunk("world", 9, 0, 0));
            assertTrue(manager.nearChunk("missing", 0, 0, 5).isEmpty());

            manager.unregister(edge);
            assertEquals(java.util.List.of(origin), manager.nearChunk("world", 0, 0, 3));
        }
    }
}
