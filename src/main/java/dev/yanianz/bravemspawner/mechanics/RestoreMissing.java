package dev.yanianz.bravemspawner.mechanics;

import dev.yanianz.bravemspawner.model.Spawner;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts spawner blocks back when a loaded record has lost its physical anchor.
 *
 * <p>World edits, protection plugins and half-finished migrations can leave a
 * database row without its block. Deleting the row would destroy the player's
 * stored loot and XP, so the block is recreated instead and the record survives.
 * The method is synchronous on purpose: it is called from the server thread after
 * the initial load, never from the async storage worker, because block writes must
 * happen on the owning region thread.
 */
public final class RestoreMissing {

    private RestoreMissing() {
    }

    /**
     * Restores every recorded block that is missing or is not the spawner material.
     *
     * @return spawners that could not be restored, for example because their world
     *         is not loaded or the coordinate is outside the world height
     */
    public static List<Spawner> reconcile(Plugin plugin, List<Spawner> spawners) {
        List<Spawner> failed = new ArrayList<>();
        if (plugin == null || spawners == null || spawners.isEmpty()) return failed;
        if (!plugin.getConfig().getBoolean("restore-missing-spawners", false)) return failed;

        Material material = spawnerMaterial(plugin);
        int restored = 0;

        for (Spawner spawner : spawners) {
            if (spawner == null) continue;

            World world = Bukkit.getWorld(spawner.world());
            if (world == null) {
                failed.add(spawner);
                continue;
            }
            if (spawner.y() < world.getMinHeight() || spawner.y() >= world.getMaxHeight()) {
                failed.add(spawner);
                continue;
            }

            try {
                Block block = world.getBlockAt(spawner.x(), spawner.y(), spawner.z());
                if (block.getType() == material) continue;
                block.setType(material, false);
                restored++;
            } catch (RuntimeException e) {
                // A region/thread rejection or an unloadable chunk must not abort
                // the rest of the pass; the record stays in the failure list.
                failed.add(spawner);
                plugin.getLogger().warning("could not restore spawner block at "
                        + spawner.world() + " " + spawner.x() + "," + spawner.y() + "," + spawner.z()
                        + ": " + e.getMessage());
            }
        }

        if (restored > 0) {
            plugin.getLogger().info("Restored " + restored + " missing spawner block(s).");
        }
        return failed;
    }

    private static Material spawnerMaterial(Plugin plugin) {
        String raw = plugin.getConfig().getString("spawner-item.material", "SPAWNER");
        Material material = raw == null ? null : Material.matchMaterial(raw);
        return material == null ? Material.SPAWNER : material;
    }
}
