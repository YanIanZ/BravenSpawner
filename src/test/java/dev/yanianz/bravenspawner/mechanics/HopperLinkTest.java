package dev.yanianz.bravenspawner.mechanics;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** REV-06 / TH-02: a stocked spawner without a hopper stops costing a region task per pass. */
class HopperLinkTest {
    final List<Runnable> dispatched = new ArrayList<>();
    final BravenSpawner plugin = mock(BravenSpawner.class);
    final SpawnerManager manager = mock(SpawnerManager.class);
    final Spawner spawner = mock(Spawner.class);
    final World world = mock(World.class);
    final Block base = mock(Block.class);
    final Block below = mock(Block.class);
    final UUID id = UUID.randomUUID();
    MockedStatic<Bukkit> bukkit;
    HopperLink link;

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        RegionScheduler regions = mock(RegionScheduler.class);
        bukkit.when(Bukkit::getRegionScheduler).thenReturn(regions);
        doAnswer(call -> dispatched.add(call.getArgument(2)))
                .when(regions).execute(any(), any(Location.class), any(Runnable.class));

        YamlConfiguration config = new YamlConfiguration();
        config.set("allow-hoppers", true);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.ready()).thenReturn(true);
        when(plugin.manager()).thenReturn(manager);

        when(spawner.id()).thenReturn(id);
        when(spawner.storedCount()).thenReturn(10);
        when(spawner.blockLocation()).thenAnswer(call -> new Location(world, 0, 64, 0));
        when(manager.all()).thenReturn(List.of(spawner));
        when(manager.byId(id)).thenReturn(spawner);
        when(manager.at(any(Location.class))).thenAnswer(call -> {
            Location at = call.getArgument(0);
            return at.getBlockY() == 64 ? spawner : null;
        });

        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(any(Location.class))).thenReturn(base);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(base);
        when(base.getRelative(eq(BlockFace.DOWN), anyInt())).thenReturn(below);
        when(below.getType()).thenReturn(Material.STONE);

        link = new HopperLink(plugin);
        // start() would install the global timer; the flag alone enables tick().
        bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(
                mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class));
        link.start();
    }

    @AfterEach
    void tearDown() {
        link.stop();
        bukkit.close();
    }

    private void tickAndRun() {
        link.tick();
        List<Runnable> batch = new ArrayList<>(dispatched);
        dispatched.clear();
        batch.forEach(Runnable::run);
    }

    @Test void noHopperBacksOffUntilTheBackOffExpires() {
        link.tick();
        assertEquals(1, dispatched.size());
        dispatched.remove(0).run();
        assertTrue(link.idle(id));

        for (long i = 1; i < HopperLink.IDLE_BACKOFF_PASSES; i++) {
            link.tick();
            assertTrue(dispatched.isEmpty(), "pass " + i);
        }
        link.tick();
        assertEquals(1, dispatched.size(), "probe resumes after the back-off");
    }

    @Test void placingAHopperBelowWakesTheSpawner() {
        tickAndRun();
        assertTrue(link.idle(id));
        link.wake(new Location(world, 0, 62, 0)); // two blocks below
        assertFalse(link.idle(id));
        link.tick();
        assertEquals(1, dispatched.size());
    }

    @Test void unrelatedHopperDoesNotWake() {
        tickAndRun();
        link.wake(new Location(world, 0, 40, 0));
        assertTrue(link.idle(id));
    }

    @Test void unloadedChunkAlsoBacksOff() {
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        tickAndRun();
        assertTrue(link.idle(id));
        verify(base, never()).getRelative(any(BlockFace.class), anyInt());
    }

    @Test void emptySpawnerIsNeverDispatched() {
        when(spawner.storedCount()).thenReturn(0);
        link.tick();
        assertTrue(dispatched.isEmpty());
    }
}
