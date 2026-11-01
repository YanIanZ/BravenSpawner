package dev.yanianz.bravenspawner.hook;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandPrivilege;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import dev.yanianz.bravenspawner.BravenSpawner;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SuperiorSkyblock2: island privileges, shared spawners, multipliers and membership, failing closed. */
class SuperiorSkyblockHookTest {
    final BravenSpawner plugin = mock(BravenSpawner.class);
    final Player player = mock(Player.class);
    final SuperiorPlayer actor = mock(SuperiorPlayer.class);
    final Island island = mock(Island.class);
    final IslandPrivilege chest = mock(IslandPrivilege.class);
    final IslandPrivilege build = mock(IslandPrivilege.class);
    final IslandPrivilege spawnerBreak = mock(IslandPrivilege.class);
    final Location onIsland = new Location(mock(World.class), 0, 64, 0);
    final Location offIsland = new Location(mock(World.class), 5000, 64, 0);
    MockedStatic<SuperiorSkyblockAPI> api;
    MockedStatic<IslandPrivilege> privileges;

    @BeforeEach void setUp() {
        api = mockStatic(SuperiorSkyblockAPI.class);
        privileges = mockStatic(IslandPrivilege.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SuperiorSkyblockHookTest"));
        api.when(() -> SuperiorSkyblockAPI.getIslandAt(onIsland)).thenReturn(island);
        api.when(() -> SuperiorSkyblockAPI.getIslandAt(offIsland)).thenReturn(null);
        api.when(() -> SuperiorSkyblockAPI.getPlayer(player)).thenReturn(actor);
        privileges.when(() -> IslandPrivilege.getByName("CHEST_ACCESS")).thenReturn(chest);
        privileges.when(() -> IslandPrivilege.getByName("BUILD")).thenReturn(build);
        privileges.when(() -> IslandPrivilege.getByName("SPAWNER_BREAK")).thenReturn(spawnerBreak);
    }

    @AfterEach void tearDown() {
        privileges.close();
        api.close();
    }

    private SuperiorSkyblockHook hook(String yaml) throws Exception {
        YamlConfiguration root = new YamlConfiguration();
        root.loadFromString(yaml);
        return new SuperiorSkyblockHook(plugin, IslandHooks.Settings.load(root));
    }

    @Test void defaultsUseBuiltInPrivileges() {
        IslandHooks.Settings settings = IslandHooks.Settings.load(new YamlConfiguration());
        assertEquals("CHEST_ACCESS", settings.usePrivilege());
        assertEquals("BUILD", settings.placePrivilege());
        assertEquals("SPAWNER_BREAK", settings.breakPrivilege());
        assertTrue(settings.enabled() && settings.islandPermissions() && settings.shareSpawners());
    }

    @Test void privilegeDecidesOnTheIslandAndNothingElsewhere() throws Exception {
        SuperiorSkyblockHook hook = hook("");
        when(island.hasPermission(actor, chest)).thenReturn(true);
        when(island.hasPermission(actor, spawnerBreak)).thenReturn(false);
        assertEquals(IslandHook.Verdict.ALLOW, hook.check(player, onIsland, IslandHook.Action.USE));
        assertEquals(IslandHook.Verdict.DENY, hook.check(player, onIsland, IslandHook.Action.BREAK));
        assertEquals(IslandHook.Verdict.NOT_ISLAND, hook.check(player, offIsland, IslandHook.Action.BREAK));
    }

    @Test void bypassModeAllowsAndFailuresDeny() throws Exception {
        SuperiorSkyblockHook hook = hook("");
        when(actor.hasBypassModeEnabled()).thenReturn(true);
        assertEquals(IslandHook.Verdict.ALLOW, hook.check(player, onIsland, IslandHook.Action.PLACE));
        when(actor.hasBypassModeEnabled()).thenReturn(false);
        when(island.hasPermission(any(SuperiorPlayer.class), any(IslandPrivilege.class))).thenThrow(new IllegalStateException("db"));
        assertEquals(IslandHook.Verdict.DENY, hook.check(player, onIsland, IslandHook.Action.PLACE));
    }

    @Test void unknownPrivilegeDenies() throws Exception {
        privileges.when(() -> IslandPrivilege.getByName("MISSING_ONE")).thenThrow(new NullPointerException("no such"));
        SuperiorSkyblockHook hook = hook("superiorskyblock:\n  privileges:\n    use: missing_one\n");
        assertEquals(IslandHook.Verdict.DENY, hook.check(player, onIsland, IslandHook.Action.USE));
    }

    @Test void disabledPermissionsLeaveTheDecisionToOthers() throws Exception {
        SuperiorSkyblockHook hook = hook("superiorskyblock:\n  island-permissions: false\n");
        assertEquals(IslandHook.Verdict.NOT_ISLAND, hook.check(player, onIsland, IslandHook.Action.USE));
        assertFalse(hook.sharesSpawners());
    }

    @Test void multipliersFollowUpgradesWithinBounds() throws Exception {
        SuperiorSkyblockHook hook = hook("superiorskyblock:\n  max-multiplier: 10\n");
        when(island.getSpawnerRatesMultiplier()).thenReturn(2.5);
        when(island.getMobDropsMultiplier()).thenReturn(1_000.0);
        assertEquals(2.5, hook.spawnRateMultiplier(onIsland));
        assertEquals(10.0, hook.dropMultiplier(onIsland));
        assertEquals(1.0, hook.dropMultiplier(offIsland));
        when(island.getSpawnerRatesMultiplier()).thenReturn(Double.NaN);
        assertEquals(1.0, hook.spawnRateMultiplier(onIsland));
        assertEquals(1.0, hook("superiorskyblock:\n  multipliers: false\n").dropMultiplier(onIsland));
    }

    /** Aurora MultiProfile: membership follows the active profile, so another profile is not a member. */
    @Test void membershipGatesAutosellPayees() throws Exception {
        SuperiorSkyblockHook hook = hook("");
        UUID member = UUID.randomUUID(), other = UUID.randomUUID();
        SuperiorPlayer memberPlayer = mock(SuperiorPlayer.class), otherPlayer = mock(SuperiorPlayer.class);
        api.when(() -> SuperiorSkyblockAPI.getPlayer(member)).thenReturn(memberPlayer);
        api.when(() -> SuperiorSkyblockAPI.getPlayer(other)).thenReturn(otherPlayer);
        when(island.isMember(memberPlayer)).thenReturn(true);
        assertTrue(hook.member(member, onIsland));
        assertFalse(hook.member(other, onIsland));
        assertTrue(hook.member(other, offIsland), "off-island spawners are not gated");
        assertTrue(hook.requiresMembershipForAutosell());
    }

    @Test void onlyOurNamespaceIsEverRegistered() {
        IslandHooks.Settings settings = IslandHooks.Settings.load(yaml(
                "superiorskyblock:\n  privileges:\n    use: bravenspawner_use\n    place: SOME_OTHER\n"));
        privileges.when(() -> IslandPrivilege.getByName(anyString())).thenThrow(new NullPointerException("unknown"));
        SuperiorSkyblockHook.registerCustom(settings);
        privileges.verify(() -> IslandPrivilege.register("BRAVENSPAWNER_USE", IslandPrivilege.Type.ACTION));
        privileges.verify(() -> IslandPrivilege.register(eq("SOME_OTHER"), any()), never());
        privileges.verify(() -> IslandPrivilege.register(eq("SPAWNER_BREAK"), any()), never());
    }

    @Test void failClosedHookDeniesEverything() {
        IslandHook closed = IslandHooks.failClosed();
        assertEquals(IslandHook.Verdict.DENY, closed.check(player, offIsland, IslandHook.Action.USE));
        assertFalse(closed.member(UUID.randomUUID(), offIsland));
        assertEquals(IslandHook.NONE, IslandHook.of(null));
    }

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration root = new YamlConfiguration();
        try { root.loadFromString(text); } catch (Exception e) { throw new AssertionError(e); }
        return root;
    }
}
