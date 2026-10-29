package dev.yanianz.bravenspawner.menu;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loads the menu files from {@code plugins/BravenSpawner/gui/}.
 *
 * <p>The five shipped menus are written from the jar only when the target file is
 * missing ({@code replace=false}), so an operator's edits survive every restart
 * and upgrade. A malformed file is logged and skipped instead of aborting the
 * whole load: one broken menu must not take the spawner GUI down with it.
 *
 * <p>The active map is swapped as a whole after parsing, so a reload never lets a
 * caller read a half-loaded menu set.
 */
public final class MenuLoader {

    /** Files copied from the jar on first boot. */
    private static final List<String> BUNDLED = List.of(
            "spawner.yml",
            "storage.yml",
            "buy_stack.yml",
            "corruption.yml",
            "log.yml");

    private static final String RESOURCE_DIRECTORY = "gui/";

    private final JavaPlugin plugin;
    private final File directory;
    private Map<String, MenuDefinition> menus = Map.of();

    public MenuLoader(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.directory = new File(plugin.getDataFolder(), "gui");
    }

    /**
     * Copies the bundled defaults, re-reads every {@code .yml} and swaps the map.
     *
     * @return number of menus that parsed successfully
     */
    public int load() {
        ensureDefaults();
        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            plugin.getLogger().warning("GUI directory " + directory + " could not be listed; no menus loaded.");
            menus = Map.of();
            return 0;
        }

        Map<String, MenuDefinition> loaded = new LinkedHashMap<>();
        for (File file : files) {
            String name = normalize(file.getName());
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                ConfigurationSection menu = yaml.getConfigurationSection("menu");
                ConfigurationSection items = yaml.getConfigurationSection("items");
                if (menu == null || items == null) {
                    plugin.getLogger().warning("GUI file " + file.getName()
                            + " needs both a menu: and an items: section; skipped.");
                    continue;
                }
                loaded.put(name, MenuDefinition.parse(name, menu, items));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("GUI file " + file.getName() + " was skipped: " + e.getMessage());
            }
        }
        menus = Collections.unmodifiableMap(loaded);
        return loaded.size();
    }

    /** Loaded menu by file name, with or without the {@code .yml} suffix. */
    public MenuDefinition get(String name) {
        if (name == null) return null;
        return menus.get(normalize(name));
    }

    /** Names of the menus currently loaded, for commands and diagnostics. */
    public Set<String> names() {
        return menus.keySet();
    }

    /** Directory the files are read from; exposed for reload reporting. */
    public File directory() {
        return directory;
    }

    private void ensureDefaults() {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            plugin.getLogger().severe("Could not create GUI directory " + directory);
            return;
        }
        for (String file : BUNDLED) {
            File target = new File(directory, file);
            if (target.isFile()) continue;
            String resource = RESOURCE_DIRECTORY + file;
            if (plugin.getResource(resource) == null) {
                plugin.getLogger().warning("Bundled resource " + resource + " is missing from the jar.");
                continue;
            }
            plugin.saveResource(resource, false);
        }
    }

    private static String normalize(String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.endsWith(".yml")) key = key.substring(0, key.length() - 4);
        int slash = Math.max(key.lastIndexOf('/'), key.lastIndexOf('\\'));
        if (slash >= 0) key = key.substring(slash + 1);
        return key;
    }
}
