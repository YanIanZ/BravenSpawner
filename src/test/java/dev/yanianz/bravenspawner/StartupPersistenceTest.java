package dev.yanianz.bravenspawner;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.storage.Storage;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class StartupPersistenceTest {
    @Test void disablingBeforeInitialLoadDoesNotWriteAnEmptySnapshot() throws Exception {
        BravenSpawner plugin = mock(BravenSpawner.class, CALLS_REAL_METHODS);
        Storage storage = mock(Storage.class);
        var field = BravenSpawner.class.getDeclaredField("storage"); field.setAccessible(true); field.set(plugin, storage);
        field = BravenSpawner.class.getDeclaredField("manager"); field.setAccessible(true); field.set(plugin, mock(SpawnerManager.class));
        plugin.onDisable();
        verify(storage, never()).flushAll(any());
        verify(storage).close();
    }
}
