package dev.yanianz.bravenspawner.hook;

import com.bgsoftware.superiorskyblock.api.events.IslandDisbandEvent;
import com.bgsoftware.superiorskyblock.api.island.Island;
import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Escrow;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Disbanding removes the island's records, including those in its (possibly unloaded) AWF worlds. */
class IslandDisbandCleanupTest {

    @Test void auroraWorldNamesMatchTheIsland() {
        UUID island = UUID.fromString("3f66d9b1-ba43-45f1-adbf-78dd16b923f5");
        assertEquals("ssb_3f66d9b1ba4345f1adbf78dd16b923f5_", IslandDisbandCleanup.auroraWorldPrefix(island));
    }

    @Test void removesInsideAndAuroraWorldSpawnersButKeepsEscrows() {
        UUID islandId = UUID.randomUUID();
        Island island = mock(Island.class);
        when(island.getUniqueId()).thenReturn(islandId);
        String awf = IslandDisbandCleanup.auroraWorldPrefix(islandId) + "normal";

        Spawner unloadedAwf = spawner(awf, null, List.of());
        Location inside = mock(Location.class);
        when(inside.getWorld()).thenReturn(mock(org.bukkit.World.class));
        Spawner insideBounds = spawner("SuperiorWorld", inside, List.of());
        when(island.isInside(inside)).thenReturn(true);
        Location outside = mock(Location.class);
        Spawner elsewhere = spawner("SuperiorWorld", outside, List.of());
        Spawner settling = spawner(awf, null, List.of(mock(Escrow.class)));

        BravenSpawner plugin = mock(BravenSpawner.class);
        SpawnerManager manager = mock(SpawnerManager.class);
        when(plugin.manager()).thenReturn(manager);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("IslandDisbandCleanupTest"));
        when(manager.all()).thenReturn(List.of(unloadedAwf, insideBounds, elsewhere, settling));

        IslandDisbandEvent event = mock(IslandDisbandEvent.class);
        when(event.getIsland()).thenReturn(island);
        new IslandDisbandCleanup(plugin).onDisband(event);

        verify(manager).unregister(unloadedAwf);
        verify(manager).unregister(insideBounds);
        verify(manager, never()).unregister(elsewhere);
        verify(manager, never()).unregister(settling);
        verify(plugin, times(2)).record(any(), eq(dev.yanianz.bravenspawner.audit.AuditEvent.Action.BREAK),
                isNull(), anyLong(), contains("disbanded"));
    }

    private static Spawner spawner(String world, Location location, List<Escrow> escrows) {
        Spawner spawner = mock(Spawner.class);
        when(spawner.world()).thenReturn(world);
        when(spawner.blockLocation()).thenReturn(location);
        when(spawner.escrows()).thenReturn(escrows);
        when(spawner.id()).thenReturn(UUID.randomUUID());
        return spawner;
    }
}
