package dev.yanianz.bravenspawner;

import dev.yanianz.bravenspawner.boosters.Boosters;
import dev.yanianz.bravenspawner.command.CommandHandler;
import dev.yanianz.bravenspawner.corruption.CorruptionTask;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.econ.Economy;
import dev.yanianz.bravenspawner.gui.SpawnerGui;
import dev.yanianz.bravenspawner.holo.Holograms;
import dev.yanianz.bravenspawner.listener.BlockListener;
import dev.yanianz.bravenspawner.listener.EntityListener;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.storage.SqlStorage;
import dev.yanianz.bravenspawner.storage.Storage;
import dev.yanianz.bravenspawner.storage.YamlStorage;
import dev.yanianz.bravenspawner.util.Schedulers;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

/**
 * BravenSpawner — virtual spawners for Paper and Folia.
 *
 * <p>Boot is strictly ordered: config → storage → manager → subsystems →
 * listeners → command → async load. No spawner ticks before the first load has
 * finished, so a slow database can never double-spawn.
 */
public final class BravenSpawner extends JavaPlugin {

    private static BravenSpawner instance;

    private Settings settings;
    private SpawnerManager manager;
    private Storage storage;
    private Economy economy;
    private Boosters boosters;
    private Holograms holograms;
    private SpawnerGui gui;

    private NamespacedKey spawnerIdKey;
    private NamespacedKey itemKindKey;
    private NamespacedKey itemProductKey;
    private NamespacedKey itemStackKey;

    private FileConfiguration databases;

    public static BravenSpawner get() {
        return instance;
    }

    public Settings settings() { return settings; }
    public SpawnerManager manager() { return manager; }
    public Economy economy() { return economy; }
    public Boosters boosters() { return boosters; }
    public Holograms holograms() { return holograms; }
    public SpawnerGui gui() { return gui; }
    public NamespacedKey spawnerIdKey() { return spawnerIdKey; }
    public NamespacedKey itemKindKey() { return itemKindKey; }
    public NamespacedKey itemProductKey() { return itemProductKey; }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadDatabases();

        spawnerIdKey = new NamespacedKey(this, "spawner");
        itemKindKey = new NamespacedKey(this, "kind");
        itemProductKey = new NamespacedKey(this, "product");
        itemStackKey = new NamespacedKey(this, "stack");

        settings = Settings.load(getConfig(), databases, null);
        storage = buildStorage();
        try {
            storage.init();
        } catch (RuntimeException e) {
            getLogger().severe("storage init failed: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economy = new Economy();
        if (settings.economy.enabled) economy.hook();

        boosters = new Boosters(this);
        manager = new SpawnerManager(this, settings);
        holograms = new Holograms(this);
        gui = new SpawnerGui(this);

        getServer().getPluginManager().registerEvents(new BlockListener(this), this);
        getServer().getPluginManager().registerEvents(new EntityListener(this), this);
        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(
                new dev.yanianz.bravenspawner.listener.GuiGuardListener(this), this);

        var command = getCommand("bspawner");
        if (command != null) {
            CommandHandler handler = new CommandHandler(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        boosters.start(settings);
        new CorruptionTask(this).start();

        Schedulers.async(this, () -> {
            List<Spawner> loaded;
            try {
                loaded = storage.loadAll();
            } catch (RuntimeException e) {
                getLogger().severe("load failed: " + e.getMessage());
                loaded = List.of();
            }
            List<Spawner> result = loaded;
            Schedulers.later(this, 1L, () -> {
                result.forEach(manager::register);
                // Reconciliation runs after registration so restored blocks are
                // re-attached to the records that already exist, not to new ones.
                dev.yanianz.bravenspawner.mechanics.RestoreMissing.reconcile(this, new java.util.ArrayList<>(result));
                manager.startAll();
                holograms.refreshAll();
                getLogger().info("BravenSpawner ready — " + manager.size()
                        + " spawner(s), storage=" + settings.storageType
                        + (Schedulers.isFolia() ? ", folia" : ""));
            });
        });
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.stopAll();
        if (holograms != null) holograms.removeAll();
        if (storage != null && manager != null) {
            try {
                storage.flushAll(manager.all());
            } catch (RuntimeException e) {
                getLogger().severe("final flush failed: " + e.getMessage());
            }
            storage.close();
        }
        instance = null;
    }

    private void loadDatabases() {
        File file = new File(getDataFolder(), "databases.yml");
        if (!file.exists()) saveResource("databases.yml", false);
        databases = YamlConfiguration.loadConfiguration(file);
    }

    private Storage buildStorage() {
        String type = settings.storageType;
        if (type.equals("SQLITE") || type.equals("MYSQL")) {
            var section = databases.getConfigurationSection("mysql") != null
                    ? databases
                    : getConfig();
            var dialect = type.equals("MYSQL") ? SqlStorage.Dialect.MYSQL : SqlStorage.Dialect.SQLITE;
            return new SqlStorage(this, section, dialect);
        }
        return new YamlStorage(this);
    }

    /** Builds the physical spawner item, carrying kind, product and stack count. */
    public ItemStack createSpawnerItem(Spawner.Kind kind, String product, int stack) {
        ItemStack item = new ItemStack(settings.spawnerMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        Settings.Product def = kind == Spawner.Kind.MOB ? settings.mob(product) : settings.item(product);
        String display = def == null ? product : def.display;
        meta.displayName(Text.of(settings.spawnerName
                .replace("%displayname%", display)
                .replace("%color%", "")));
        meta.lore(Text.of(settings.spawnerLore));
        meta.getPersistentDataContainer().set(itemKindKey, PersistentDataType.STRING, kind.name());
        meta.getPersistentDataContainer().set(itemProductKey, PersistentDataType.STRING, product);
        meta.getPersistentDataContainer().set(itemStackKey, PersistentDataType.INTEGER, Math.max(1, stack));
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createSpawnerItem(Spawner spawner) {
        ItemStack item = createSpawnerItem(spawner.kind(), spawner.product(), spawner.stack());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            var pdc = meta.getPersistentDataContainer();
            pdc.set(itemStackKey, PersistentDataType.INTEGER, spawner.stack());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Persists, reloads config, and rebuilds every tick schedule. */
    public void reloadAll() {
        if (manager != null) storage.flushAll(manager.all());
        reloadConfig();
        loadDatabases();
        settings = Settings.load(getConfig(), databases, null);
        manager.settings(settings);
    }
}
