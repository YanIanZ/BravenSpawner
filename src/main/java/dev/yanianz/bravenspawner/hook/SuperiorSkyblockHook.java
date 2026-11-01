package dev.yanianz.bravenspawner.hook;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandPrivilege;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import dev.yanianz.bravenspawner.BravenSpawner;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SuperiorSkyblock2 integration (the only class that references its API).
 *
 * <ul>
 *   <li>Island privileges gate use, place and break of spawners on an island
 *       (defaults: {@code CHEST_ACCESS}, {@code BUILD}, {@code SPAWNER_BREAK}).</li>
 *   <li>Members holding the use privilege may use every spawner of their island.</li>
 *   <li>The island's spawner-rates and mob-drops upgrade multipliers apply.</li>
 *   <li>Autosell pays an owner only while they are a member of the spawner's
 *       island. With SourbyCraft Aurora MultiProfile and SSB2 profile support,
 *       membership follows the active profile, so a spawner on one profile's island
 *       never pays into another profile's purse.</li>
 * </ul>
 *
 * <p>Any API failure denies (fails closed) and is logged once.
 */
final class SuperiorSkyblockHook implements IslandHook {

    private final BravenSpawner plugin;
    private final IslandHooks.Settings settings;
    private final Map<String, IslandPrivilege> privileges = new ConcurrentHashMap<>();
    private final AtomicBoolean warned = new AtomicBoolean();

    SuperiorSkyblockHook(BravenSpawner plugin, IslandHooks.Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        // Fail at construction, not on the first click, when the API is not usable.
        SuperiorSkyblockAPI.getGrid();
    }

    static void registerCustom(IslandHooks.Settings settings) {
        for (String name : new String[] {settings.usePrivilege(), settings.placePrivilege(), settings.breakPrivilege()}) {
            if (!IslandHooks.Settings.custom(name)) continue;
            if (find(name) == null) IslandPrivilege.register(name, IslandPrivilege.Type.ACTION);
        }
    }

    private static IslandPrivilege find(String name) {
        try {
            return IslandPrivilege.getByName(name);
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    @Override public String name() { return "superiorskyblock2"; }

    @Override
    public Verdict check(Player player, Location location, Action action) {
        if (!settings.islandPermissions()) return Verdict.NOT_ISLAND;
        try {
            Island island = island(location);
            if (island == null) return Verdict.NOT_ISLAND;
            SuperiorPlayer actor = SuperiorSkyblockAPI.getPlayer(player);
            if (actor != null && actor.hasBypassModeEnabled()) return Verdict.ALLOW;
            IslandPrivilege privilege = privilege(switch (action) {
                case USE -> settings.usePrivilege();
                case PLACE -> settings.placePrivilege();
                case BREAK -> settings.breakPrivilege();
            });
            if (privilege == null || actor == null) return Verdict.DENY;
            return island.hasPermission(actor, privilege) ? Verdict.ALLOW : Verdict.DENY;
        } catch (RuntimeException | LinkageError error) {
            warn(error);
            return Verdict.DENY;
        }
    }

    @Override
    public boolean member(UUID player, Location location) {
        try {
            Island island = island(location);
            if (island == null) return true;
            SuperiorPlayer owner = player == null ? null : SuperiorSkyblockAPI.getPlayer(player);
            return owner != null && island.isMember(owner);
        } catch (RuntimeException | LinkageError error) {
            warn(error);
            return false;
        }
    }

    @Override
    public double spawnRateMultiplier(Location location) {
        return multiplier(location, true);
    }

    @Override
    public double dropMultiplier(Location location) {
        return multiplier(location, false);
    }

    private double multiplier(Location location, boolean rates) {
        if (!settings.multipliers()) return 1.0;
        try {
            Island island = island(location);
            if (island == null) return 1.0;
            double value = rates ? island.getSpawnerRatesMultiplier() : island.getMobDropsMultiplier();
            if (!Double.isFinite(value) || value <= 0.0) return 1.0;
            return Math.min(value, settings.maxMultiplier());
        } catch (RuntimeException | LinkageError error) {
            warn(error);
            return 1.0;
        }
    }

    @Override public boolean sharesSpawners() { return settings.shareSpawners() && settings.islandPermissions(); }

    @Override public boolean requiresMembershipForAutosell() { return settings.autosellRequiresMembership(); }

    private static Island island(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return SuperiorSkyblockAPI.getIslandAt(location);
    }

    private IslandPrivilege privilege(String name) {
        IslandPrivilege cached = privileges.get(name);
        if (cached != null) return cached;
        IslandPrivilege found = find(name);
        if (found == null) {
            if (warned.compareAndSet(false, true)) {
                plugin.getLogger().warning("SuperiorSkyblock2 has no island privilege '" + name
                        + "'; spawner actions needing it are denied (superiorskyblock.privileges).");
            }
            return null;
        }
        privileges.put(name, found);
        return found;
    }

    private void warn(Throwable error) {
        if (warned.compareAndSet(false, true)) {
            plugin.getLogger().warning("SuperiorSkyblock2 check failed; denying: " + error);
        }
    }
}
