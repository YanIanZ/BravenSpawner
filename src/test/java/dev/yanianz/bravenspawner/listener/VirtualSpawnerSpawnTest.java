package dev.yanianz.bravenspawner.listener;
import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class VirtualSpawnerSpawnTest {
    @Test void nativeSpawnIsCancelledOnlyForManagedBlocks() {
        BravenSpawner plugin = mock(BravenSpawner.class, RETURNS_DEEP_STUBS);
        SpawnerSpawnEvent event = mock(SpawnerSpawnEvent.class, RETURNS_DEEP_STUBS);
        var location = event.getSpawner().getLocation();
        when(plugin.manager().at(location)).thenReturn(mock(Spawner.class));
        new EntityListener(plugin).onVanillaSpawn(event);
        verify(event).setCancelled(true);
    }
}
