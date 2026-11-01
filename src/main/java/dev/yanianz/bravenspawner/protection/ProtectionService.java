package dev.yanianz.bravenspawner.protection;

import dev.yanianz.bravenspawner.BravenSpawner;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Optional WorldGuard and island-plugin checks, independent of ownership and Bukkit event cancellation. */
public final class ProtectionService {
    private final BravenSpawner plugin;
    private final Logger logger;
    private final AtomicBoolean warned = new AtomicBoolean();

    public ProtectionService(BravenSpawner plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /** Check each target for menu access, stacking, collection and radius wand actions. */
    public boolean canUse(Player player, Location location) {
        return allows(player, location, Action.USE);
    }

    public boolean canPlace(Player player, Location location) {
        return allows(player, location, Action.PLACE);
    }

    public boolean canBreak(Player player, Location location) {
        return allows(player, location, Action.BREAK);
    }

    private boolean allows(Player player, Location location, Action action) {
        if (player == null || location == null || location.getWorld() == null) return false;
        // Island plugin first (SuperiorSkyblock2 privileges), then WorldGuard; both must allow.
        var island = dev.yanianz.bravenspawner.hook.IslandHook.of(plugin.islands()).check(player, location, switch (action) {
            case USE -> dev.yanianz.bravenspawner.hook.IslandHook.Action.USE;
            case PLACE -> dev.yanianz.bravenspawner.hook.IslandHook.Action.PLACE;
            case BREAK -> dev.yanianz.bravenspawner.hook.IslandHook.Action.BREAK;
        });
        if (island == dev.yanianz.bravenspawner.hook.IslandHook.Verdict.DENY) return false;
        var installed = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (installed == null) return true;
        // A present but disabled/broken protection plugin must not become an
        // implicit allow for operations performed without its event listeners.
        if (!installed.isEnabled()) return false;
        try {
            return WorldGuardAccess.allows(player, location, action);
        } catch (RuntimeException | LinkageError error) {
            if (warned.compareAndSet(false, true)) {
                logger.warning("WorldGuard checks unavailable; spawner access denied: "
                        + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
            return false;
        }
    }

    private enum Action { USE, PLACE, BREAK }

    // Keep every optional EngineHub API reference outside the outer class so a
    // server without WorldGuard/WorldEdit can load the service normally.
    private static final class WorldGuardAccess {
        private static boolean allows(Player player, Location location, Action action) {
            var worldGuard = com.sk89q.worldguard.WorldGuard.getInstance();
            var localPlayer = com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapPlayer(player);
            var world = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(location.getWorld());
            if (worldGuard.getPlatform().getSessionManager().hasBypass(localPlayer, world)) return true;
            var query = worldGuard.getPlatform().getRegionContainer().createQuery();
            var target = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(location);
            return switch (action) {
                case PLACE -> query.testBuild(target, localPlayer,
                        com.sk89q.worldguard.protection.flags.Flags.BLOCK_PLACE);
                case BREAK -> query.testBuild(target, localPlayer,
                        com.sk89q.worldguard.protection.flags.Flags.BLOCK_BREAK);
                case USE -> query.testBuild(target, localPlayer,
                        com.sk89q.worldguard.protection.flags.Flags.INTERACT,
                        com.sk89q.worldguard.protection.flags.Flags.CHEST_ACCESS);
            };
        }
    }
}
