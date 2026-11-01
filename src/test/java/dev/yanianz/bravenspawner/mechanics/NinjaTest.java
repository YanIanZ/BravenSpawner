package dev.yanianz.bravenspawner.mechanics;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** REV-06 / TH-02: ninja refresh only visits spawners the client can hold in view. */
class NinjaTest {
    final BravenSpawner plugin = mock(BravenSpawner.class);
    final World world = mock(World.class);
    final Player player = mock(Player.class);
    MockedStatic<Bukkit> bukkit;
    MockedStatic<Schedulers> schedulers;
    SpawnerManager manager;
    Ninja ninja;

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        schedulers = mockStatic(Schedulers.class);
        when(world.getName()).thenReturn("world");
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> List.of(player));
        schedulers.when(() -> Schedulers.onEntity(any(), any(), any(), any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(2)).run();
            return null;
        });

        YamlConfiguration config = new YamlConfiguration();
        config.set("ninja-spawners.enable-invisibility", true);
        config.set("ninja-spawners.start-invisibility-distance", 32);
        config.set("ninja-spawners.visibility-distance", 8);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.ready()).thenReturn(true);
        manager = new SpawnerManager(plugin, Settings.load(new YamlConfiguration(), null, null));
        when(plugin.manager()).thenReturn(manager);

        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getX()).thenReturn(0.5);
        when(player.getY()).thenReturn(64.0);
        when(player.getZ()).thenReturn(0.5);
        when(player.getSendViewDistance()).thenReturn(4);

        ninja = new Ninja(plugin);
        bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(
                mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class));
        ninja.start();
    }

    @AfterEach
    void tearDown() {
        schedulers.close();
        bukkit.close();
    }

    private Spawner register(String worldName, int x) {
        Spawner spawner = new Spawner(UUID.randomUUID(), UUID.randomUUID(), worldName, x, 64, 0,
                Spawner.Kind.MOB, "ZOMBIE", 1, 0, true);
        manager.register(spawner);
        return spawner;
    }

    @Test void hidesOnlySpawnersWithinViewDistance() {
        register("world", 4);          // inside visibility distance: untouched
        register("world", 48);         // chunk 3: hidden
        register("world", 16 * 20);    // chunk 20: beyond view distance 4 (+1)
        register("nether", 48);        // other world
        ninja.tick();
        verify(player, times(1)).sendBlockChange(any(Location.class), any());
        verify(player).sendBlockChange(argThat(l -> l.getBlockX() == 48), any());
    }

    @Test void viewDistanceFallsBackAndIsCapped() {
        Player other = mock(Player.class);
        when(other.getSendViewDistance()).thenReturn(0);
        when(other.getViewDistance()).thenReturn(7);
        assertEquals(7, Ninja.viewDistance(other));
        when(other.getViewDistance()).thenReturn(0);
        assertEquals(32, Ninja.viewDistance(other));
        when(other.getSendViewDistance()).thenReturn(99);
        assertEquals(32, Ninja.viewDistance(other));
    }
}
