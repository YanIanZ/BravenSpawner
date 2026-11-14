package dev.yanianz.bravenspawner.convert;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the mob off a SilkSpawners spawner item without depending on SilkSpawners.
 *
 * <p>SilkSpawners stamps its spawner items in the {@code silkspawners} PDC
 * namespace. The exact key name is not referenced anywhere here: any key in
 * that namespace whose string value names an entity is accepted, so minor
 * SilkSpawners key renames do not silently stop detection. SilkSpawners is not
 * installed on this server and is not a dependency; every read is guarded and
 * a malformed value (wrong type, blank, unreadable container) yields empty
 * instead of throwing inside an event handler.
 */
public final class SilkSpawnersNbt {

    /** PDC namespace SilkSpawners writes its spawner type under. */
    public static final String NAMESPACE = "silkspawners";

    private SilkSpawnersNbt() {
    }

    /**
     * Entity name carried by a SilkSpawners item, upper-cased for product lookup.
     *
     * @return the raw entity name, or empty when the item carries no readable
     *         SilkSpawners value
     */
    public static Optional<String> readMob(ItemStack item) {
        if (item == null) {
            return Optional.empty();
        }
        ItemMeta meta;
        try {
            meta = item.getItemMeta();
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
        if (meta == null) {
            return Optional.empty();
        }
        PersistentDataContainer pdc;
        try {
            pdc = meta.getPersistentDataContainer();
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
        Set<NamespacedKey> keys;
        try {
            keys = pdc.getKeys();
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
        if (keys == null || keys.isEmpty()) {
            return Optional.empty();
        }
        for (NamespacedKey key : keys) {
            if (key == null || !NAMESPACE.equalsIgnoreCase(key.getNamespace())) {
                continue;
            }
            String raw;
            try {
                raw = pdc.get(key, PersistentDataType.STRING);
            } catch (RuntimeException malformed) {
                // A value stored under another type must not throw the scan.
                continue;
            }
            if (raw == null || raw.isBlank()) {
                continue;
            }
            return Optional.of(raw.trim().toUpperCase(Locale.ROOT));
        }
        return Optional.empty();
    }
}
