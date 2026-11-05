package dev.yanianz.bravenspawner.hook;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;

import java.util.UUID;

/**
 * Which island a player belongs to, for the PlaceholderAPI expansion.
 *
 * <p>Kept beside {@link SuperiorSkyblockHook} because it is the only other class
 * that names SuperiorSkyblock2 types: PlaceholderAPI may be present on a server
 * without SSB2, so the caller guards the link and every failure here answers
 * {@code null} instead of throwing.
 *
 * <p>The lookup is a read of SSB2's in-memory player/island map. It is therefore
 * safe to call from a placeholder request, which may arrive on any thread, and it
 * never touches a Bukkit world.
 */
public final class SuperiorSkyblockIslandLookup {

    private SuperiorSkyblockIslandLookup() {}

    /**
     * The UUID of {@code player}'s island as a string, or {@code null} when the
     * player has no island, is unknown to SSB2, or the API cannot be linked.
     *
     * <p>Returned as a string so no SSB2 or island-handle type escapes this class
     * into the placeholder path, which must keep working with SSB2 absent.
     */
    public static String islandOf(UUID player) {
        if (player == null) return null;
        try {
            SuperiorPlayer superior = SuperiorSkyblockAPI.getPlayer(player);
            Island island = superior == null ? null : superior.getIsland();
            return island == null ? null : island.getUniqueId().toString();
        } catch (RuntimeException | LinkageError unavailable) {
            // SSB2 absent, still loading, or its API changed: no island, not a crash.
            return null;
        }
    }
}
