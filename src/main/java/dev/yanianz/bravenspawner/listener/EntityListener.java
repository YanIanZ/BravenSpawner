package dev.yanianz.bravenspawner.listener;

import dev.yanianz.bravenspawner.BravenSpawner;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.SpawnerSpawnEvent;

/**
 * Managed spawners produce virtual loot only, so the vanilla spawn of a managed
 * block is cancelled. Before the records are loaded (or when storage is unavailable)
 * any spawner block may be managed, so every native spawn is held back.
 */
public final class EntityListener implements Listener {

    private final BravenSpawner plugin;

    public EntityListener(BravenSpawner plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaSpawn(SpawnerSpawnEvent event) {
        if (!plugin.ready() || plugin.manager().at(event.getSpawner().getLocation()) != null) {
            event.setCancelled(true);
        }
    }
}
