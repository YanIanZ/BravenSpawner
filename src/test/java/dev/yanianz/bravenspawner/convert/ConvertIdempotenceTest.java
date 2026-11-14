package dev.yanianz.bravenspawner.convert;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** An already-managed block is never double-registered by any conversion path. */
class ConvertIdempotenceTest {

    @Test void registeringTheSameBlockTwiceKeepsOneRecord() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Settings settings = Settings.load(new YamlConfiguration(), null, null);
            SpawnerManager manager = new SpawnerManager(mock(BravenSpawner.class), settings);
            Spawner first = new Spawner(UUID.randomUUID(), ConvertService.SYSTEM_OWNER,
                    "world", 8, 64, 8, Spawner.Kind.MOB, "ZOMBIE", 1, 0, true);
            Spawner second = new Spawner(UUID.randomUUID(), ConvertService.SYSTEM_OWNER,
                    "world", 8, 64, 8, Spawner.Kind.MOB, "ZOMBIE", 1, 0, true);
            assertTrue(manager.register(first));
            assertFalse(manager.register(second), "second conversion of the same block loses the race");
            assertEquals(1, manager.size());
        }
    }

    @Test void guardShortCircuitsBeforeCapacityAndThrottle() {
        // alreadyManaged=true reports ALREADY_MANAGED no matter what the later
        // guards say, so a re-scan never consumes chunk room or throttle budget.
        assertEquals(ConvertCheck.ALREADY_MANAGED,
                ConvertGuards.checkBlock(true, true, false, true, true, false, false));
    }
}
