package dev.yanianz.bravenspawner.product;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One-way bridge from the legacy {@code config.yml} product blocks to per-product
 * files.
 *
 * <p>Older servers carry their whole catalogue in {@code mobs:} and {@code items:}
 * sections. Those keys are the only copy of a server's tuned drops, so they must be
 * turned into files before the packaged defaults are laid down; otherwise a first
 * boot would overwrite the catalogue with stock values and the custom economy
 * would be gone. That ordering is why {@link ProductLoader} calls this class before
 * it calls {@code saveResource}.
 *
 * <p>Migration is first-boot only, enforced by a {@code .migrated} marker inside
 * the product directory. The marker also covers the "admin deleted every file to
 * start clean" case: the plugin does not resurrect products from an old config
 * section behind their back.
 *
 * <p>When a directory already holds YAML files, migration does nothing. The files
 * are the source of truth from that point on; a leftover {@code mobs:} block in
 * config.yml is simply ignored. That is the behaviour when both sources exist, and
 * it is deliberate: editing config.yml must never silently override a hand-tuned
 * file.
 */
public final class ProductMigration {

    /** Marker written inside a product directory after a successful migration. */
    private static final String MARKER = ".migrated";

    /** Interval used when the legacy config has no upgrade tier to copy from. */
    private static final int DEFAULT_INTERVAL_TICKS = 1200;

    private static final int DEFAULT_MAX_STOCK = 1000;
    private static final int DEFAULT_RESTOCK = 8;

    private final JavaPlugin plugin;

    public ProductMigration(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Writes one YAML file per legacy entry when the directory is still empty.
     *
     * @param directory destination directory, already created by the loader
     * @param configKey legacy root key, {@code "mobs"} or {@code "items"}
     * @param kind      product kind used to choose the type key and the fallback
     * @return {@code true} when at least one file was written, which tells the
     *         loader to keep the directory exactly as migrated and skip defaults
     */
    public boolean migrateIfEmpty(File directory, String configKey, ProductDefinition.Kind kind) {
        if (directory == null || !directory.isDirectory()) return false;
        if (new File(directory, MARKER).isFile()) return false;
        if (containsYaml(directory)) return false;

        ConfigurationSection section = plugin.getConfig().getConfigurationSection(configKey);
        if (section == null || section.getKeys(false).isEmpty()) return false;

        int interval = defaultIntervalTicks();
        int written = 0;
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                plugin.getLogger().severe("config.yml " + configKey + "." + key
                        + " is not a section — skipped during migration");
                continue;
            }
            if (writeEntry(directory, key, entry, kind, interval)) written++;
        }

        if (written == 0) return false;
        writeMarker(directory);
        plugin.getLogger().info("Migrated " + written + " legacy " + configKey
                + " product(s) from config.yml into " + directory.getName()
                + "/; the config.yml section is now ignored");
        return true;
    }

    /** True when the directory already holds at least one YAML file. */
    private static boolean containsYaml(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        return files != null && files.length > 0;
    }

    /**
     * Translates one legacy entry.
     *
     * <p>Legacy mob keys double as the entity type, so they map straight across.
     * Legacy item keys do not: a server may key {@code COBBLE} while the real
     * output is {@code COBBLESTONE}. The first drop material is therefore the
     * authoritative item type, with the key only as a fallback.
     */
    private boolean writeEntry(File directory, String key, ConfigurationSection entry,
                               ProductDefinition.Kind kind, int interval) {
        String id = sanitize(key);
        if (id.isEmpty()) {
            plugin.getLogger().severe("config.yml product key '" + key
                    + "' has no usable file name — skipped during migration");
            return false;
        }

        List<String> dropLines = entry.getStringList("drops");
        List<Map<String, Object>> drops = new ArrayList<>();
        for (String line : dropLines) {
            Map<String, Object> drop = legacyDrop(line);
            if (drop == null) {
                plugin.getLogger().warning("config.yml product " + key
                        + " has an unreadable drop '" + line + "' — drop skipped");
                continue;
            }
            drops.add(drop);
        }
        if (drops.isEmpty()) {
            plugin.getLogger().severe("config.yml product " + key
                    + " has no valid drops — skipped during migration");
            return false;
        }

        String typeKey;
        String typeValue;
        if (kind == ProductDefinition.Kind.MOB) {
            EntityType type = entityType(entry.getString("entity-type", key));
            if (type == null) {
                plugin.getLogger().severe("config.yml mob product " + key
                        + " does not name a known entity type — skipped during migration");
                return false;
            }
            typeKey = "entity-type";
            typeValue = type.name();
        } else {
            Material material = firstDropMaterial(dropLines);
            if (material == null) material = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
            if (material == null || !material.isItem()) {
                plugin.getLogger().severe("config.yml item product " + key
                        + " does not name a known item material — skipped during migration");
                return false;
            }
            typeKey = "item-type";
            typeValue = material.name();
        }

        File target = new File(directory, id + ".yml");
        if (target.exists()) return false;

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "Migrated from config.yml " + (kind == ProductDefinition.Kind.MOB ? "mobs" : "items")
                        + "." + key + " on first boot.",
                "This file is now the source of truth; the config.yml section is ignored.",
                "The file name is the product id stored on placed spawners."));
        yaml.set(typeKey, typeValue);
        yaml.set("display-name", displayName(entry.getString("display", key)));
        yaml.set("interval-ticks", interval);
        yaml.set("xp", Math.max(0, entry.getInt("xp", 0)));
        yaml.set("drops", drops);
        yaml.set("stock.enabled", true);
        yaml.set("stock.max-stock", DEFAULT_MAX_STOCK);
        yaml.set("stock.restock-per-cycle", DEFAULT_RESTOCK);

        try {
            yaml.save(target);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("could not write migrated product " + target.getName()
                    + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Converts a legacy {@code MATERIAL:amount[:chance]} line into the map form the
     * loader reads, normalising the legacy 0..1 chance to a percent.
     */
    private static Map<String, Object> legacyDrop(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(":");
        Material material = Material.matchMaterial(parts[0].trim());
        if (material == null || !material.isItem() || material.isAir()) return null;

        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Math.max(1, Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException ignored) {
                // keep the default amount rather than dropping the whole line
            }
        }
        double chance = 1.0;
        if (parts.length > 2) {
            try {
                chance = Double.parseDouble(parts[2].trim());
            } catch (NumberFormatException ignored) {
                // keep the default chance
            }
        }
        if (chance > 1.0) chance /= 100.0;
        double percent = Math.min(100.0, Math.max(0.0, chance * 100.0));

        return new LinkedHashMap<>(Map.of(
                "material", material.name(),
                "amount", amount,
                "chance", percent));
    }

    private static Material firstDropMaterial(List<String> lines) {
        for (String line : lines) {
            if (line == null) continue;
            String[] parts = line.split(":");
            Material material = Material.matchMaterial(parts[0].trim());
            if (material != null && material.isItem() && !material.isAir()) return material;
        }
        return null;
    }

    private static EntityType entityType(String raw) {
        return EntityTypes.entity(raw);
    }

    private static String displayName(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("none")) return null;
        return raw;
    }

    /**
     * Copies the base interval from the first upgrade tier so migrated products
     * keep the pacing the server was already running.
     */
    private int defaultIntervalTicks() {
        List<Map<?, ?>> tiers = plugin.getConfig().getMapList("upgrades.tiers");
        if (!tiers.isEmpty()) {
            Object value = tiers.get(0).get("interval-ticks");
            if (value instanceof Number number && number.longValue() > 0) {
                return (int) Math.min(Integer.MAX_VALUE, number.longValue());
            }
        }
        return DEFAULT_INTERVAL_TICKS;
    }

    private void writeMarker(File directory) {
        File marker = new File(directory, MARKER);
        try {
            if (!marker.createNewFile() && !marker.setLastModified(System.currentTimeMillis())) {
                plugin.getLogger().warning("could not refresh migration marker " + marker.getPath());
            }
        } catch (IOException e) {
            plugin.getLogger().warning("could not write migration marker " + marker.getPath()
                    + ": " + e.getMessage());
        }
    }

    private static String sanitize(String raw) {
        if (raw == null) return "";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                out.append(c);
            } else if (c == ' ' || c == '.') {
                out.append('_');
            }
        }
        return out.toString();
    }
}
