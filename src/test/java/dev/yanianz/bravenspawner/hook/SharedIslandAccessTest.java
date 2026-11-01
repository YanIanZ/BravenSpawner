package dev.yanianz.bravenspawner.hook;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Review finding: shared island spawners require membership, not only the use privilege. */
class SharedIslandAccessTest {
    @Test void visitorsWithThePrivilegeDoNotShareSpawners() {
        BravenSpawner plugin = mock(BravenSpawner.class);
        when(plugin.mayUse(any(), any())).thenCallRealMethod();
        IslandHook hook = mock(IslandHook.class);
        when(plugin.islands()).thenReturn(hook);
        when(hook.sharesSpawners()).thenReturn(true);
        when(hook.check(any(), any(), eq(IslandHook.Action.USE))).thenReturn(IslandHook.Verdict.ALLOW);

        Spawner spawner = mock(Spawner.class);
        when(spawner.blockLocation()).thenReturn(new Location(mock(World.class), 0, 64, 0));
        Player visitor = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(visitor.getUniqueId()).thenReturn(id);

        when(hook.member(eq(id), any())).thenReturn(false);
        assertFalse(plugin.mayUse(visitor, spawner));
        when(hook.member(eq(id), any())).thenReturn(true);
        assertTrue(plugin.mayUse(visitor, spawner));
        when(spawner.blockLocation()).thenReturn(null);
        assertFalse(plugin.mayUse(visitor, spawner), "an unloaded world cannot be checked");
    }
}
