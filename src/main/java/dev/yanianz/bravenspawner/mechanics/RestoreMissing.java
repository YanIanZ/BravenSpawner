package dev.yanianz.bravenspawner.mechanics;

import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.util.Schedulers;
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
 * Each block is restored on its owning region after the initial records are loaded.
 */
public final class RestoreMissing {

    private RestoreMissing() {
    }

    /**
     * Restores every recorded block that is missing or is not the spawner material.
     *
     * @return records rejected before dispatch. Failures inside the asynchronous
     *         region callbacks are logged; this method does not wait for them.
     */
    public static List<Spawner> reconcile(Plugin plugin, List<Spawner> spawners) {
        List<Spawner> failed = new ArrayList<>();
        if (plugin == null || spawners == null || spawners.isEmpty()) return failed;
        if (!plugin.getConfig().getBoolean("restore-missing-spawners", false)) return failed;

        Material material = spawnerMaterial(plugin);

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
                Schedulers.at(plugin, new org.bukkit.Location(world, spawner.x(), spawner.y(), spawner.z()), () -> {
                    if (!plugin.isEnabled()) return;
                    if (plugin instanceof BravenSpawner braven
                            && braven.manager().byId(spawner.id()) != spawner) return;
                    try {
                        Block block = world.getBlockAt(spawner.x(), spawner.y(), spawner.z());
                        Material previous = block.getType();
                        if (previous == material) return;
                        block.setType(material, false);
                        // Opt-in, but it overwrites whatever is there now: leave a trail.
                        plugin.getLogger().warning("restored spawner block " + spawner.id() + " at "
                                + spawner.world() + " " + spawner.x() + "," + spawner.y() + "," + spawner.z()
                                + ", replacing " + previous);
                        if (plugin instanceof BravenSpawner braven) {
                            braven.record(spawner, dev.yanianz.bravenspawner.audit.AuditEvent.Action.RESTORE,
                                    null, 0, "replaced " + previous);
                        }
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("could not restore spawner block " + spawner.id()
                                + ": " + e.getMessage());
                    }
                });
            } catch (RuntimeException e) {
                // A region/thread rejection or an unloadable chunk must not abort
                // the rest of the pass; the record stays in the failure list.
                failed.add(spawner);
                plugin.getLogger().warning("could not restore spawner block at "
                        + spawner.world() + " " + spawner.x() + "," + spawner.y() + "," + spawner.z()
                        + ": " + e.getMessage());
            }
        }

        return failed;
    }

    private static Material spawnerMaterial(Plugin plugin) {
        String raw = plugin.getConfig().getString("spawner-item.material", "SPAWNER");
        Material material = raw == null ? null : Material.matchMaterial(raw);
        return material == null || material.isAir() ? Material.SPAWNER : material;
    }
}
