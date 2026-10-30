package dev.yanianz.bravenspawner.storage;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SqlWriteQueueTest {
    @Test void gameplayMutationsOnlyQueueWritesAndDeletes() {
        SqlStorage storage = new SqlStorage(mock(Plugin.class), new YamlConfiguration(), SqlStorage.Dialect.SQLITE);
        Spawner spawner = new Spawner(UUID.randomUUID(),UUID.randomUUID(),"world",0,64,0,Spawner.Kind.MOB,"ZOMBIE",1,0,true);
        assertDoesNotThrow(() -> storage.upsert(spawner));
        assertTrue(storage.isDirty());
        assertEquals(1, storage.pendingWrites());
        assertDoesNotThrow(() -> storage.delete(spawner.id()));
        assertTrue(storage.isDirty());
    }
}
