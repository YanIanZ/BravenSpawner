package dev.yanianz.bravenspawner;

import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnershipSettingTest {
    private static BravenSpawner plugin(boolean ownership) throws Exception {
        BravenSpawner plugin = mock(BravenSpawner.class, CALLS_REAL_METHODS);
        YamlConfiguration root = new YamlConfiguration();
        root.set("ownership.enabled", ownership);
        var field = BravenSpawner.class.getDeclaredField("settings");
        field.setAccessible(true);
        field.set(plugin, Settings.load(root, null, null));
        return plugin;
    }

    @Test void strangersAreRefusedOnlyWhileOwnershipIsEnabled() throws Exception {
        Spawner spawner = new Spawner(UUID.randomUUID(), UUID.randomUUID(), "world", 0, 64, 0,
                Spawner.Kind.MOB, "ZOMBIE", 1, 0, true);
        Player stranger = mock(Player.class);
        when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
        assertFalse(plugin(true).mayUse(stranger, spawner));
        assertFalse(plugin(true).mayBreak(stranger, spawner));
        assertTrue(plugin(false).mayUse(stranger, spawner));
        assertTrue(plugin(false).mayBreak(stranger, spawner));
        assertTrue(Settings.load(new YamlConfiguration(), null, null).ownershipEnabled, "defaults to enabled");
    }
}
