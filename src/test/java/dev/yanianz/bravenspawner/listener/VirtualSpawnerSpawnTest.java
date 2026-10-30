package dev.yanianz.bravenspawner.listener;
import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class VirtualSpawnerSpawnTest {
    @Test void nativeSpawnIsCancelledOnlyForManagedBlocks() {
        BravenSpawner plugin = mock(BravenSpawner.class, RETURNS_DEEP_STUBS);
        when(plugin.ready()).thenReturn(true);
        SpawnerSpawnEvent event = mock(SpawnerSpawnEvent.class, RETURNS_DEEP_STUBS);
        var location = event.getSpawner().getLocation();
        when(plugin.manager().at(location)).thenReturn(mock(Spawner.class));
        new EntityListener(plugin).onVanillaSpawn(event);
        verify(event).setCancelled(true);
    }

    @Test void unmanagedSpawnIsKeptOnceRecordsAreLoaded() {
        BravenSpawner plugin = mock(BravenSpawner.class, RETURNS_DEEP_STUBS);
        when(plugin.ready()).thenReturn(true);
        SpawnerSpawnEvent event = mock(SpawnerSpawnEvent.class, RETURNS_DEEP_STUBS);
        when(plugin.manager().at(any())).thenReturn(null);
        new EntityListener(plugin).onVanillaSpawn(event);
        verify(event, never()).setCancelled(true);
    }

    @Test void everySpawnIsHeldBackWhileStorageIsNotReady() {
        BravenSpawner plugin = mock(BravenSpawner.class, RETURNS_DEEP_STUBS);
        when(plugin.ready()).thenReturn(false);
        SpawnerSpawnEvent event = mock(SpawnerSpawnEvent.class, RETURNS_DEEP_STUBS);
        new EntityListener(plugin).onVanillaSpawn(event);
        verify(event).setCancelled(true);
    }
}
