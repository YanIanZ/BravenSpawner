package dev.yanianz.bravenspawner.hook;

import com.bgsoftware.superiorskyblock.api.events.IslandDisbandEvent;
import com.bgsoftware.superiorskyblock.api.island.Island;
import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Removes the spawner records of a disbanded SuperiorSkyblock2 island.
 *
 * <p>Disbanding erases the island's blocks (or, with SourbyCraft Aurora World
 * Fabric, deletes its worlds). The records would otherwise stay forever, invisible
 * and producing. A spawner belongs to the island when it is inside the island's
 * bounds, or when it lies in one of the island's own AWF worlds
 * ({@code ssb_<island uuid hex>_<dimension>}), which may already be unloaded.
 * A spawner with an open sale escrow is kept and reported, so no pending payment
 * loses its items.
 */
public final class IslandDisbandCleanup implements Listener {

    private final BravenSpawner plugin;

    public IslandDisbandCleanup(BravenSpawner plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDisband(IslandDisbandEvent event) {
        Island island = event.getIsland();
        if (island == null || plugin.manager() == null) return;
        String worldPrefix = auroraWorldPrefix(island.getUniqueId());
        List<Spawner> removed = new ArrayList<>();
        int kept = 0;
        for (Spawner spawner : List.copyOf(plugin.manager().all())) {
            if (!belongs(island, worldPrefix, spawner)) continue;
            if (!spawner.escrows().isEmpty()) { kept++; continue; }
            plugin.record(spawner, dev.yanianz.bravenspawner.audit.AuditEvent.Action.BREAK, null,
                    spawner.storedCount(), "island " + island.getUniqueId() + " disbanded");
            plugin.manager().unregister(spawner);
            if (plugin.holograms() != null) plugin.holograms().remove(spawner.id());
            removed.add(spawner);
        }
        if (!removed.isEmpty() || kept > 0) {
            plugin.getLogger().info("island " + island.getUniqueId() + " disbanded: removed " + removed.size()
                    + " spawner record(s)" + (kept > 0 ? ", kept " + kept + " with an open sale escrow" : ""));
        }
    }

    static boolean belongs(Island island, String worldPrefix, Spawner spawner) {
        if (spawner.world() != null && spawner.world().startsWith(worldPrefix)) return true;
        Location location = spawner.blockLocation();
        return location != null && island.isInside(location);
    }

    /** SSB-AuroraWorlds names island worlds {@code ssb_<uuid without dashes>_<dimension>}. */
    static String auroraWorldPrefix(UUID island) {
        return "ssb_" + island.toString().replace("-", "").toLowerCase(Locale.ROOT) + "_";
    }
}
