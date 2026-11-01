package dev.yanianz.bravenspawner.platform;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.holo.Holograms;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** AWF / AdvancedSlimePaper island worlds: unload pauses, load resumes, records stay. */
class WorldLifecycleTest {
    final BravenSpawner plugin = mock(BravenSpawner.class);
    final SpawnerManager manager = mock(SpawnerManager.class);
    final Holograms holograms = mock(Holograms.class);
    final Spawner spawner = mock(Spawner.class);
    final UUID id = UUID.randomUUID();

    WorldLifecycle lifecycle() {
        when(plugin.manager()).thenReturn(manager);
        when(plugin.holograms()).thenReturn(holograms);
        when(plugin.ready()).thenReturn(true);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(spawner.id()).thenReturn(id);
        when(manager.inWorld("ssb_island_normal")).thenReturn(List.of(spawner));
        when(manager.inWorld("world")).thenReturn(List.of());
        return new WorldLifecycle(plugin);
    }

    @Test void unloadPausesAndDropsHolograms() {
        WorldLifecycle lifecycle = lifecycle();
        lifecycle.unloaded("ssb_island_normal");
        verify(manager).pauseWorld("ssb_island_normal");
        verify(holograms).remove(id);
        verify(manager, never()).unregister(any());
    }

    @Test void loadResumesAndRebuildsHolograms() {
        WorldLifecycle lifecycle = lifecycle();
        lifecycle.loaded("ssb_island_normal");
        verify(manager).resumeWorld("ssb_island_normal");
        verify(holograms).update(spawner);
    }

    @Test void worldsWithoutSpawnersAreIgnoredAndNothingRunsBeforeReady() {
        WorldLifecycle lifecycle = lifecycle();
        lifecycle.loaded("world");
        lifecycle.unloaded("world");
        verify(manager, never()).resumeWorld(anyString());
        verify(manager, never()).pauseWorld(anyString());
        when(plugin.ready()).thenReturn(false);
        lifecycle.loaded("ssb_island_normal");
        verify(manager, never()).resumeWorld(anyString());
    }

    @Test void canvasAsyncUnloadIsOptional() {
        assertNull(WorldLifecycle.optionalEvent("io.example.Missing"));
        assertNull(WorldLifecycle.optionalEvent("org.bukkit.event.player.PlayerJoinEvent"), "not a world event");
        assertEquals(org.bukkit.event.world.WorldUnloadEvent.class,
                WorldLifecycle.optionalEvent("org.bukkit.event.world.WorldUnloadEvent"));
    }
}
