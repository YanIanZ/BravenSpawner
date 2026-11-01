package dev.yanianz.bravenspawner.manager;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.model.Spawner;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * REV-07 / TH-04: production runs as one delayed-task chain per spawner. A cycle
 * re-arms itself; replaced, unregistered or stopped chains end; failures and skipped
 * cycles keep the chain alive.
 */
class ProductionChainTest {
    record Pending(Consumer<ScheduledTask> run, ScheduledTask handle, long delay) { }

    final List<Pending> pending = new ArrayList<>();
    final BravenSpawner plugin = mock(BravenSpawner.class);
    final World world = mock(World.class);
    final RegionScheduler regions = mock(RegionScheduler.class);
    MockedStatic<Bukkit> bukkit;
    SpawnerManager manager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        when(world.getName()).thenReturn("world");
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        bukkit.when(Bukkit::getRegionScheduler).thenReturn(regions);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ProductionChainTest"));
        when(regions.runDelayed(any(), any(Location.class), any(Consumer.class), anyLong())).thenAnswer(call -> {
            ScheduledTask handle = mock(ScheduledTask.class);
            pending.add(new Pending(call.getArgument(2), handle, call.getArgument(3)));
            return handle;
        });
        manager = new SpawnerManager(plugin, Settings.load(new YamlConfiguration(), null, null));
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private static Spawner spawner() {
        return new Spawner(UUID.randomUUID(), UUID.randomUUID(), "world", 1, 64, 1,
                Spawner.Kind.MOB, "ZOMBIE", 1, 0, true);
    }

    /** Runs the oldest pending task and removes it from the queue. */
    private void runNext() {
        Pending next = pending.remove(0);
        next.run().accept(next.handle());
    }

    @Test void registerArmsOneDelayedTaskAndNoRepeatingTask() {
        Spawner spawner = spawner();
        manager.register(spawner);
        assertEquals(1, pending.size());
        assertTrue(pending.get(0).delay() >= 20L);
        assertEquals(1, manager.liveTasks(spawner.id()));
        verify(regions, never()).runAtFixedRate(any(), any(Location.class), any(), anyLong(), anyLong());
    }

    @Test void eachCycleArmsExactlyOneSuccessor() {
        Spawner spawner = spawner();
        manager.register(spawner);
        for (int i = 0; i < 5; i++) {
            runNext();
            assertEquals(1, pending.size(), "cycle " + i);
        }
        assertEquals(1, manager.liveTasks(spawner.id()));
    }

    @Test void skippedOrFailedProductionKeepsTheChain() {
        Spawner spawner = spawner();
        manager.register(spawner);
        when(plugin.ready()).thenThrow(new IllegalStateException("boom"));
        runNext();
        assertEquals(1, pending.size());
        spawner.restoreCorruption(true, 1L);
        runNext();
        assertEquals(1, pending.size());
        assertEquals(1, manager.liveTasks(spawner.id()));
    }

    @Test void rescheduleEndsTheOldChain() {
        Spawner spawner = spawner();
        manager.register(spawner);
        Pending old = pending.get(0);
        manager.reschedule(spawner);
        verify(old.handle()).cancel();
        assertEquals(2, pending.size());
        runNext(); // stale link of the replaced chain
        assertEquals(1, pending.size(), "stale link must not re-arm");
        runNext();
        assertEquals(1, pending.size());
        assertEquals(1, manager.liveTaskCount());
    }

    @Test void unregisterAndStopAllEndTheChain() {
        Spawner first = spawner();
        manager.register(first);
        manager.unregister(first);
        runNext();
        assertTrue(pending.isEmpty());
        assertEquals(0, manager.liveTasks(first.id()));

        Spawner second = new Spawner(UUID.randomUUID(), UUID.randomUUID(), "world", 40, 64, 1,
                Spawner.Kind.MOB, "ZOMBIE", 1, 0, true);
        manager.register(second);
        manager.stopAll();
        runNext();
        assertTrue(pending.isEmpty());
        assertEquals(0, manager.liveTaskCount());
    }

    @Test void unschedulableCycleLeavesAGapThatStartAllFills() {
        Spawner spawner = spawner();
        manager.register(spawner);
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
        runNext();
        assertTrue(pending.isEmpty());
        assertEquals(0, manager.liveTasks(spawner.id()));

        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        manager.startAll();
        manager.startAll();
        assertEquals(1, pending.size());
        assertEquals(1, manager.liveTasks(spawner.id()));
    }

    @Test void schedulerFailureDoesNotLeaveALiveLease() {
        Spawner spawner = spawner();
        manager.register(spawner);
        when(regions.runDelayed(any(), any(Location.class), any(), anyLong()))
                .thenThrow(new IllegalStateException("region gone"));
        runNext();
        assertEquals(0, manager.liveTasks(spawner.id()));
    }

    /** A record whose block vanished without a break event pauses output but keeps its chain. */
    @Test void missingBlockPausesProductionWithoutEndingTheChain() {
        Spawner spawner = spawner();
        manager.register(spawner);
        when(plugin.ready()).thenReturn(true);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        org.bukkit.block.Block block = mock(org.bukkit.block.Block.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);

        when(block.getType()).thenReturn(org.bukkit.Material.AIR);
        runNext();
        verify(plugin, never()).particles();
        assertEquals(1, pending.size());

        when(block.getType()).thenReturn(org.bukkit.Material.SPAWNER);
        runNext();
        verify(plugin, times(1)).particles();
        assertEquals(1, pending.size());
    }

    /** AWF / ASP: an unloading world cancels its leases; loading re-arms them. */
    @Test void pauseAndResumeWorld() {
        Spawner spawner = spawner();
        manager.register(spawner);
        assertEquals(1, manager.pauseWorld("world"));
        assertEquals(0, manager.liveTasks(spawner.id()));
        runNext();
        assertTrue(pending.isEmpty(), "the cancelled chain does not re-arm");
        assertEquals(1, manager.resumeWorld("world"));
        assertEquals(1, manager.liveTasks(spawner.id()));
        assertEquals(1, pending.size());
        assertEquals(0, manager.pauseWorld("nether"));
    }

    /** SuperiorSkyblock2 spawner-rates upgrade shortens the interval. */
    @Test void islandSpawnRateShortensTheInterval() {
        var islands = mock(dev.yanianz.bravenspawner.hook.IslandHook.class);
        when(islands.spawnRateMultiplier(any())).thenReturn(2.0);
        manager.register(spawner());
        long normal = pending.getFirst().delay();
        when(plugin.islands()).thenReturn(islands);
        manager.register(new Spawner(UUID.randomUUID(), UUID.randomUUID(), "world", 99, 64, 1,
                Spawner.Kind.MOB, "ZOMBIE", 1, 0, true));
        assertEquals(Math.max(20L, Math.round(normal / 2.0)), pending.get(1).delay());
    }
}
