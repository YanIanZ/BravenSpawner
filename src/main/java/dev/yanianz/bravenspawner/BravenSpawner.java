package dev.yanianz.bravenspawner;

import dev.yanianz.bravenspawner.audit.AuditEvent;
import dev.yanianz.bravenspawner.audit.AuditLog;
import dev.yanianz.bravenspawner.boosters.Boosters;
import dev.yanianz.bravenspawner.command.CommandHandler;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.corruption.CorruptionTask;
import dev.yanianz.bravenspawner.econ.Economy;
import dev.yanianz.bravenspawner.gui.SpawnerGui;
import dev.yanianz.bravenspawner.holo.Holograms;
import dev.yanianz.bravenspawner.listener.BlockListener;
import dev.yanianz.bravenspawner.listener.EntityListener;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.platform.PlatformDetector;
import dev.yanianz.bravenspawner.platform.Placeholders;
import dev.yanianz.bravenspawner.product.ProductDefinition;
import dev.yanianz.bravenspawner.product.ProductLoader;
import dev.yanianz.bravenspawner.product.ProductRegistry;
import dev.yanianz.bravenspawner.storage.CheckpointService;
import dev.yanianz.bravenspawner.storage.SqlStorage;
import dev.yanianz.bravenspawner.storage.Storage;
import dev.yanianz.bravenspawner.storage.YamlStorage;
import dev.yanianz.bravenspawner.util.Schedulers;
import dev.yanianz.bravenspawner.util.Text;
import dev.yanianz.bravenspawner.wand.SellWandService;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

/** Startup publishes a usable manager only after storage loading and registration succeed. */
public final class BravenSpawner extends JavaPlugin {
    private static BravenSpawner instance;
    private volatile boolean ready;
    private Settings settings;
    private SpawnerManager manager;
    private Storage storage;
    private Economy economy;
    private Boosters boosters;
    private Holograms holograms;
    private SpawnerGui gui;
    private CheckpointService checkpoints;
    private CorruptionTask corruption;
    private AuditLog audit;
    private ProductRegistry products;
    private SellWandService wands;
    private PlatformDetector platform;
    private Placeholders placeholders;
    private dev.yanianz.bravenspawner.commerce.ShopBridge shops;
    private dev.yanianz.bravenspawner.protection.ProtectionService protection;
    private dev.yanianz.bravenspawner.restrictions.RestrictedSpawners restrictions;
    private dev.yanianz.bravenspawner.events.SpawnerEvents events;
    private NamespacedKey spawnerIdKey, itemKindKey, itemProductKey, itemStackKey;
    private FileConfiguration databases;

    public static BravenSpawner get() { return instance; }
    public boolean ready() { return ready; }
    public Settings settings() { return settings; }
    public SpawnerManager manager() { return manager; }
    public Economy economy() { return economy; }
    public Boosters boosters() { return boosters; }
    public Holograms holograms() { return holograms; }
    public SpawnerGui gui() { return gui; }
    public Storage storage() { return storage; }
    public CorruptionTask corruption() { return corruption; }
    public AuditLog audit() { return audit; }
    public ProductRegistry products() { return products; }
    public SellWandService wands() { return wands; }
    public PlatformDetector platform() { return platform; }
    public Placeholders placeholders() { return placeholders; }
    public dev.yanianz.bravenspawner.commerce.ShopBridge shops() { return shops; }
    public dev.yanianz.bravenspawner.protection.ProtectionService protection() { return protection; }
    public dev.yanianz.bravenspawner.restrictions.RestrictedSpawners restrictions() { return restrictions; }
    public dev.yanianz.bravenspawner.events.SpawnerEvents events() { return events; }
    public NamespacedKey spawnerIdKey() { return spawnerIdKey; }
    public NamespacedKey itemKindKey() { return itemKindKey; }
    public NamespacedKey itemProductKey() { return itemProductKey; }

    public ProductDefinition product(Spawner spawner) {
        if (products == null || spawner == null) return null;
        return spawner.kind() == Spawner.Kind.MOB ? products.mob(spawner.product()) : products.item(spawner.product());
    }
    public int stackLimit(Spawner spawner) {
        ProductDefinition product = product(spawner);
        return product == null || product.maxStack() <= 0 ? settings.stackLimit
                : Math.min(settings.stackLimit, product.maxStack());
    }
    public int maximumItems(Spawner spawner) {
        ProductDefinition product = product(spawner);
        return product == null || settings.unlimitedStorage ? 0 : scaledLimit(product.maxStoredItems(), spawner.stack());
    }
    public int maximumXp(Spawner spawner) {
        ProductDefinition product = product(spawner);
        return product == null || settings.unlimitedStorage ? 0 : scaledLimit(product.maxStoredXp(), spawner.stack());
    }
    private static int scaledLimit(int limit, int stack) {
        return limit <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (long) limit * Math.max(1, stack));
    }
    public void markDirty(Spawner spawner) {
        if (checkpoints != null && spawner != null) storage.upsert(spawner);
    }
    public void markDeleted(java.util.UUID spawnerId) {
        if (checkpoints != null && spawnerId != null) storage.delete(spawnerId);
    }
    public void record(Spawner spawner, AuditEvent.Action action, Player actor, long amount, String detail) {
        if (audit != null) audit.record(AuditEvent.of(spawner, action,
                actor == null ? null : actor.getUniqueId(), actor == null ? "Console" : actor.getName(), amount, detail));
    }

    @Override public void onEnable() {
        instance = this;
        new dev.yanianz.bravenspawner.platform.PlatformReport(this).log();
        try {
            boot();
        } catch (Throwable failure) {
            // Leaving a half-initialised plugin registered is worse than stopping it:
            // commands stay bound, tasks may already run, and the operator only sees
            // "plugin is disabled". Print the cause and the platform line together.
            getLogger().severe("BravenSpawner could not start: " + failure);
            getLogger().severe("Report the platform line above with this stack trace. "
                    + "NoSuchMethodError/NoClassDefFoundError means the server API differs from "
                    + "the compiled target (Paper 1.21); check 'api-version' support on this fork.");
            failure.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void boot() {
        boolean existingConfig = new File(getDataFolder(), "config.yml").isFile();
        saveDefaultConfig();
        loadDatabases();
        spawnerIdKey = new NamespacedKey(this, "spawner");
        itemKindKey = new NamespacedKey(this, "kind");
        itemProductKey = new NamespacedKey(this, "product");
        itemStackKey = new NamespacedKey(this, "stack");
        products = new ProductRegistry(new ProductLoader(this, existingConfig));
        settings = Settings.load(getConfig(), databases, null, products);
        storage = buildStorage();
        try {
            storage.init();
        } catch (Throwable error) {
            // A broken database must not take the whole plugin down: the feature the
            // operator asked for still works with flat-file storage, and the reason is
            // logged once so it can be fixed before the next restart.
            getLogger().severe("storage init failed (" + settings.storageType + "): " + error);
            getLogger().severe("falling back to YAML storage for this run; fix databases.yml to use "
                    + settings.storageType + " again");
            storage = new YamlStorage(this);
            storage.init();
        }
        economy = new Economy();
        if (settings.economy.enabled) economy.hook();
        boosters = new Boosters(this);
        manager = new SpawnerManager(this, settings);
        audit = new AuditLog(this);
        platform = new PlatformDetector(this);
        placeholders = new Placeholders();
        shops = new dev.yanianz.bravenspawner.commerce.ShopBridge(this);
        protection = new dev.yanianz.bravenspawner.protection.ProtectionService(this);
        restrictions = new dev.yanianz.bravenspawner.restrictions.RestrictedSpawners(this);
        events = new dev.yanianz.bravenspawner.events.SpawnerEvents(this);
        holograms = new Holograms(this);
        gui = new SpawnerGui(this);
        getServer().getPluginManager().registerEvents(new BlockListener(this), this);
        getServer().getPluginManager().registerEvents(new EntityListener(this), this);
        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(new dev.yanianz.bravenspawner.listener.GuiGuardListener(this), this);
        wands = SellWandService.install(this);
        new CommandHandler(this).register(this);
        boosters.start(settings);
        corruption = new CorruptionTask(this);
        Schedulers.async(this, () -> {
            List<Spawner> loaded;
            try { loaded = storage.loadAll(); }
            catch (RuntimeException error) {
                getLogger().severe("load failed: " + error.getMessage());
                if (isEnabled()) Schedulers.later(this, 1L,
                        () -> getServer().getPluginManager().disablePlugin(this));
                return;
            }
            if (!isEnabled()) return;
            Schedulers.later(this, 1L, () -> {
                if (!isEnabled()) return;
                loaded.forEach(manager::register);
                dev.yanianz.bravenspawner.mechanics.RestoreMissing.reconcile(this, new java.util.ArrayList<>(loaded));
                ready = true;
                checkpoints = new CheckpointService(this, storage, manager::all);
                corruption.start();
                events.start();
                manager.startAll();
                holograms.refreshAll();
                getLogger().info("BravenSpawner ready — " + manager.size() + " spawner(s), storage=" + settings.storageType);
            });
        });
    }

    @Override public void onDisable() {
        ready = false;
        if (wands != null) wands.shutdown();
        if (boosters != null) boosters.shutdown();
        if (events != null) events.close();
        if (corruption != null) corruption.stop();
        if (manager != null) manager.stopAll();
        if (checkpoints != null) { checkpoints.close(); checkpoints = null; }
        if (holograms != null) holograms.removeAll();
        if (audit != null) audit.close();
        if (storage != null) storage.close();
        instance = null;
    }
    private void loadDatabases() {
        File file = new File(getDataFolder(), "databases.yml");
        if (!file.exists()) saveResource("databases.yml", false);
        databases = YamlConfiguration.loadConfiguration(file);
    }
    private Storage buildStorage() {
        String type = settings.storageType;
        if (type.equals("MONGODB") || type.equals("MONGO")) return new dev.yanianz.bravenspawner.storage.MongoStorage(this, databases);
        if (type.equals("SQLITE") || type.equals("MYSQL")) {
            var section = databases.getConfigurationSection("mysql") != null ? databases : getConfig();
            return new SqlStorage(this, section, type.equals("MYSQL") ? SqlStorage.Dialect.MYSQL : SqlStorage.Dialect.SQLITE);
        }
        if (type.equals("YAML")) return new YamlStorage(this);
        throw new IllegalArgumentException("Unsupported storage type: " + type);
    }
    public ItemStack createSpawnerItem(Spawner.Kind kind, String product, int stack) {
        ItemStack item = new ItemStack(settings.spawnerMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        ProductDefinition definition = kind == Spawner.Kind.MOB ? products.mob(product) : products.item(product);
        String display = definition == null ? product : definition.displayName();
        String name = definition != null && definition.spawnerItemName() != null ? definition.spawnerItemName() : settings.spawnerName;
        meta.displayName(Text.item(name.replace("%displayname%", display).replace("%color%", "")));
        meta.lore(Text.items(definition != null && definition.spawnerItemLore() != null ? definition.spawnerItemLore() : settings.spawnerLore));
        var pdc = meta.getPersistentDataContainer();
        pdc.set(itemKindKey, PersistentDataType.STRING, kind.name());
        pdc.set(itemProductKey, PersistentDataType.STRING, product);
        pdc.set(itemStackKey, PersistentDataType.INTEGER, Math.max(1, stack));
        item.setItemMeta(meta);
        return item;
    }
    public ItemStack createSpawnerItem(Spawner spawner) {
        return createSpawnerItem(spawner.kind(), spawner.product(), spawner.stack());
    }
    public void reloadAll() {
        if (!ready) throw new IllegalStateException("Spawner storage is not ready");
        if (checkpoints == null || !checkpoints.flushNow()) throw new IllegalStateException("Could not save spawner state before reload");
        reloadConfig();
        loadDatabases();
        products.reload();
        settings = Settings.load(getConfig(), databases, null, products);
        platform = new PlatformDetector(this);
        placeholders = new Placeholders();
        gui.reloadMenus();
        wands.reload();
        boosters.reload(settings);
        restrictions.reload();
        events.reload();
        shops = new dev.yanianz.bravenspawner.commerce.ShopBridge(this);
        manager.settings(settings);
        holograms.reload();
    }
}
