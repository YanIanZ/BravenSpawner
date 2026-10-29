package dev.yanianz.bravenspawner.wand;

import dev.yanianz.bravenspawner.BravenSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Set;

/**
 * File bootstrap and scalar parsing shared by the sell-wand loaders.
 *
 * <p>The wand keeps its settings in three standalone YAML files instead of
 * {@code config.yml} so an admin can swap the wand's look without touching the
 * spawner schema. {@link BravenSpawner#saveResource} writes the bundled copy on
 * first boot; an unreadable file degrades to an empty configuration, and every
 * loader then applies its documented default rather than failing the plugin.
 *
 * <p>Scalar readers are deliberately strict about the YAML type: Bukkit's
 * {@code getDouble} silently substitutes the fallback for a list or a boolean,
 * which would hide a typo forever. These helpers report the malformed value once
 * per load and return the same fallback, so behaviour is predictable and the log
 * says why.
 */
final class WandFiles {

    private WandFiles() {}

    static YamlConfiguration load(BravenSpawner plugin, String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            try {
                plugin.saveResource(name, false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("bundled " + name + " is missing from the jar: " + e.getMessage());
            }
        }
        try {
            return YamlConfiguration.loadConfiguration(file);
        } catch (RuntimeException e) {
            plugin.getLogger().warning(name + " could not be read (" + e.getMessage()
                    + "); documented defaults are in effect.");
            return new YamlConfiguration();
        }
    }

    static void warnOnce(BravenSpawner plugin, Set<String> seen, String key, String message) {
        if (seen.add(key)) plugin.getLogger().warning(message);
    }

    /** Reads a finite double, falling back when the node is absent or malformed. */
    static double number(BravenSpawner plugin, Set<String> warned,
                         ConfigurationSection section, String path, double fallback) {
        Object raw = section.get(path);
        if (raw == null) return fallback;

        double value;
        if (raw instanceof Number number) {
            value = number.doubleValue();
        } else if (raw instanceof String text) {
            try {
                value = Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                warnOnce(plugin, warned, path, "configuration key " + path + " is not a number; using " + fallback + ".");
                return fallback;
            }
        } else {
            warnOnce(plugin, warned, path, "configuration key " + path + " is not a number; using " + fallback + ".");
            return fallback;
        }

        if (!Double.isFinite(value)) {
            warnOnce(plugin, warned, path, "configuration key " + path + " is not finite; using " + fallback + ".");
            return fallback;
        }
        return value;
    }

    /** Reads an whole number, falling back when the node is fractional or malformed. */
    static int integer(BravenSpawner plugin, Set<String> warned,
                       ConfigurationSection section, String path, int fallback) {
        Object raw = section.get(path);
        if (raw == null) return fallback;

        double value;
        if (raw instanceof Number number) {
            value = number.doubleValue();
        } else if (raw instanceof String text) {
            try {
                value = Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                warnOnce(plugin, warned, path, "configuration key " + path + " is not a whole number; using " + fallback + ".");
                return fallback;
            }
        } else {
            warnOnce(plugin, warned, path, "configuration key " + path + " is not a whole number; using " + fallback + ".");
            return fallback;
        }

        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            warnOnce(plugin, warned, path, "configuration key " + path + " is not a whole number; using " + fallback + ".");
            return fallback;
        }
        return (int) value;
    }

    /** Reads a boolean or the strings {@code true}/{@code false}; anything else falls back. */
    static boolean flag(BravenSpawner plugin, Set<String> warned,
                        ConfigurationSection section, String path, boolean fallback) {
        Object raw = section.get(path);
        if (raw == null) return fallback;
        if (raw instanceof Boolean value) return value;
        if (raw instanceof String text) {
            if (text.equalsIgnoreCase("true")) return true;
            if (text.equalsIgnoreCase("false")) return false;
        }
        warnOnce(plugin, warned, path, "configuration key " + path + " is not true/false; using " + fallback + ".");
        return fallback;
    }
}
