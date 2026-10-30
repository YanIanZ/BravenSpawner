package dev.yanianz.bravenspawner;

import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.storage.Storage;
import dev.yanianz.bravenspawner.storage.YamlStorage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** ST-01: a broken database never silently becomes spawners.yml. */
class StorageFailClosedTest {
    @TempDir Path folder;

    private BravenSpawner plugin(boolean fallback) throws Exception {
        BravenSpawner plugin = mock(BravenSpawner.class, CALLS_REAL_METHODS);
        doReturn(Logger.getLogger("StorageFailClosedTest")).when(plugin).getLogger();
        doReturn(folder.toFile()).when(plugin).getDataFolder();
        YamlConfiguration databases = new YamlConfiguration();
        databases.set("type", "postgres");
        databases.set("fallback-to-yaml", fallback);
        set(plugin, "databases", databases);
        set(plugin, "settings", Settings.load(new YamlConfiguration(), databases, null));
        return plugin;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = BravenSpawner.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Storage open(BravenSpawner plugin) throws Exception {
        Method method = BravenSpawner.class.getDeclaredMethod("openStorage");
        method.setAccessible(true);
        Storage storage = (Storage) method.invoke(plugin);
        set(plugin, "storage", storage);
        return storage;
    }

    @Test void brokenDatabaseEntersStorageUnavailableMode() throws Exception {
        Files.writeString(folder.resolve("spawners.yml"), "spawners: {}\n");
        BravenSpawner plugin = plugin(false);
        assertNull(open(plugin));
        assertFalse(plugin.isStorageAvailable());
        assertFalse(plugin.isStorageFallback());
        assertTrue(plugin.storageFailure().contains("POSTGRES"));
        assertFalse(plugin.ready());
    }

    @Test void explicitFallbackUsesAnEmptyFallbackFile() throws Exception {
        BravenSpawner plugin = plugin(true);
        Storage storage = open(plugin);
        assertInstanceOf(YamlStorage.class, storage);
        assertEquals(YamlStorage.FALLBACK_FILE, ((YamlStorage) storage).file().getName());
        assertTrue(plugin.isStorageAvailable());
        assertTrue(plugin.isStorageFallback());
        assertNotNull(plugin.storageFailure());
    }
}
