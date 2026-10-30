package dev.yanianz.bravenspawner.storage;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageCodecTest {
    @TempDir Path folder;

    @Test void encodeTrustedHoldsTheSetMonitor() throws Exception {
        Set<UUID> trusted = Collections.synchronizedSet(new LinkedHashSet<>());
        for (int i = 0; i < 2_000; i++) trusted.add(UUID.randomUUID());
        AtomicBoolean stop = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            while (!stop.get()) {
                UUID id = UUID.randomUUID();
                trusted.add(id);
                trusted.remove(id);
            }
        });
        writer.start();
        try {
            for (int i = 0; i < 200; i++) assertDoesNotThrow(() -> Storage.Codec.encodeTrusted(trusted));
        } finally {
            stop.set(true);
            writer.join();
        }
    }

    @Test void yamlFallbackNeverReadsSpawnersYmlAndArchivesOldFallback() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("StorageCodecTest"));
        Files.writeString(folder.resolve("spawners.yml"), "spawners:\n  " + UUID.randomUUID() + ":\n    owner: x\n");
        Files.writeString(folder.resolve(YamlStorage.FALLBACK_FILE), "spawners: {}\n");

        YamlStorage fallback = YamlStorage.freshFallback(plugin);
        fallback.init();
        assertEquals(YamlStorage.FALLBACK_FILE, fallback.file().getName());
        assertTrue(fallback.loadAll().isEmpty());
        assertFalse(Files.exists(folder.resolve(YamlStorage.FALLBACK_FILE)), "the previous fallback file is archived");
        try (var files = Files.list(folder)) {
            assertEquals(1, files.filter(p -> p.getFileName().toString().startsWith("spawners-fallback-")).count());
        }
        assertTrue(Files.exists(folder.resolve("spawners.yml")), "spawners.yml is left untouched");
    }
}
