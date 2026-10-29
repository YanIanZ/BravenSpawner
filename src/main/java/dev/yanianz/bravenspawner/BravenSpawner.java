package dev.yanianz.bravenspawner;

import dev.yanianz.bravenspawner.audit.AuditEvent;
import dev.yanianz.bravenspawner.audit.AuditLog;
import dev.yanianz.bravenspawner.audit.AuditMenu;
import dev.yanianz.bravenspawner.stock.BuyStackService;
import dev.yanianz.bravenspawner.boosters.Boosters;
import dev.yanianz.bravenspawner.command.CommandHandler;
import dev.yanianz.bravenspawner.commerce.PriceResolver;
import dev.yanianz.bravenspawner.commerce.ShopBridge;
import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.corruption.CorruptionTask;
import dev.yanianz.bravenspawner.econ.Economy;
import dev.yanianz.bravenspawner.gui.SpawnerGui;
import dev.yanianz.bravenspawner.holo.Holograms;
import dev.yanianz.bravenspawner.listener.BlockListener;
import dev.yanianz.bravenspawner.listener.EntityListener;
import dev.yanianz.bravenspawner.listener.GuiGuardListener;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.platform.HologramConfig;
import dev.yanianz.bravenspawner.platform.Placeholders;
import dev.yanianz.bravenspawner.platform.PlatformDetector;
import dev.yanianz.bravenspawner.platform.PlatformText;
import dev.yanianz.bravenspawner.product.ProductDefinition;
import dev.yanianz.bravenspawner.product.ProductLoader;
import dev.yanianz.bravenspawner.product.ProductRegistry;
import dev.yanianz.bravenspawner.product.StockService;
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
import java.util.UUID;

/**
 * BravenSpawner — virtual spawners for Paper and Folia.
 *
 * <p>Boot order is fixed and linear: config → storage → catalogue → platform →
 * commerce → manager → subsystems → listeners → command → async load. Nothing
 * schedules a tick before the first load completes, so a slow database can never
 * produce a duplicate cycle, and a failed load can never be followed by a
 * destructive flush.
 *
 * <p>This class is the only one allowed to know about every subsystem. Everything
 * else receives what it needs through its constructor, which keeps the dependency
 * graph acyclic and makes the integration surface auditable in one file.
 */
public final class BravenSpawner extends JavaPlugin {

    private static BravenSpawner instance;

    /** False until the first load has been registered; the tick pass gates on it. */
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
    private AuditMenu auditMenu;
    private BuyStackService buyStack;
    private SellWandService wands;

    private ProductRegistry products;
    private StockService stock;
    private PlatformDetector platform;
    private Placeholders placeholders;
    private PlatformText platformText;
    private HologramConfig hologramConfig;
    private ShopBridge shopBridge;
    private PriceResolver priceResolver;

    private NamespacedKey spawnerIdKey;
    private NamespacedKey itemKindKey;
    private NamespacedKey itemProductKey;
    private NamespacedKey itemStackKey;

    private FileConfiguration databases;

    public static BravenSpawner get() {
        return instance;
    }

    // ------------------------------------------------------------- accessors

    public boolean ready() { return ready; }

    /** Storage ceiling for a spawner: unlimited, or the capacity of its tier. */
    public int maximumItems(Spawner spawner) {
        if (settings.unlimitedStorage) return 0;
        int tier = spawner == null ? 0 : Math.max(0, Math.min(spawner.tier(), settings.tiers.size() - 1));
        return settings.tiers.get(tier).capacity;
    }

    /** XP a spawner may bank before production stops adding to the pool. */
    public int maximumXp(Spawner spawner) {
        ProductDefinition def = product(spawner);
        return def == null || def.maxXpPerCycle() <= 0 ? 100_000 : def.maxXpPerCycle();
    }

    public Settings settings() { return settings; }
    public SpawnerManager manager() { return manager; }
    public Storage storage() { return storage; }
    public Economy economy() { return economy; }
    public Boosters boosters() { return boosters; }
    public Holograms holograms() { return holograms; }
    public SpawnerGui gui() { return gui; }
    public CorruptionTask corruption() { return corruption; }
    public AuditLog audit() { return audit; }
    public AuditMenu auditMenu() { return auditMenu; }
    public BuyStackService buyStack() { return buyStack; }
    public SellWandService wands() { return wands; }

    public ProductRegistry products() { return products; }
    public StockService stock() { return stock; }
    public PlatformDetector platform() { return platform; }
    public Placeholders placeholders() { return placeholders; }
    public PlatformText platformText() { return platformText; }
    public HologramConfig hologramConfig() { return hologramConfig; }
    public ShopBridge shopBridge() { return shopBridge; }
    public PriceResolver priceResolver() { return priceResolver; }

    public NamespacedKey spawnerIdKey() { return spawnerIdKey; }
    public NamespacedKey itemKindKey() { return itemKindKey; }
    public NamespacedKey itemProductKey() { return itemProductKey; }

    /** Resolves the catalogue entry for a spawner, or null when its product vanished. */
    public ProductDefinition product(Spawner spawner) {
        if (products == null || spawner == null) return null;
        return spawner.kind() == Spawner.Kind.MOB
                ? products.mob(spawner.product())
                : products.item(spawner.product());
    }

    /** Effective stack cap: the product may lower the global limit but never raise it. */
    public int stackLimit(Spawner spawner) {
        ProductDefinition product = product(spawner);
        int global = settings.stackLimit;
        return product == null || product.maxStack() <= 0 ? global : Math.min(global, product.maxStack());
    }

    // ------------------------------------------------------------- lifecycle

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

        // Catalogue before the manager: migration must finish before the first tick.
        products = new ProductRegistry(new ProductLoader(this));

        platform = new PlatformDetector(this);
        placeholders = new Placeholders();
        platformText = new PlatformText(settings, placeholders);
        hologramConfig = HologramConfig.load(this);

        shopBridge = new ShopBridge(getLogger());
        priceResolver = PriceResolver.fromSettings(shopBridge, this::settings);

        economy = new Economy();
        if (settings.economy.enabled) economy.hook();

        boosters = new Boosters(this);
        manager = new SpawnerManager(this, settings);
        stock = new StockService(manager, products);
        holograms = new Holograms(this);
        gui = new SpawnerGui(this);
        audit = new AuditLog(this);
        auditMenu = new AuditMenu(this);
        buyStack = new BuyStackService(this).audit(audit);
        wands = SellWandService.install(this);
        corruption = new CorruptionTask(this);

        getServer().getPluginManager().registerEvents(new BlockListener(this), this);
        getServer().getPluginManager().registerEvents(new EntityListener(this), this);
        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(new GuiGuardListener(this), this);
        // SellWandService.install() registers its own listener; registering a
        // second one would handle every wand click twice.

        new CommandHandler(this).register(this);
        boosters.start(settings);
        corruption.start();

        checkpoints = new CheckpointService(this, storage, () -> manager.all());

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
                dev.yanianz.bravenspawner.mechanics.RestoreMissing.reconcile(this, new java.util.ArrayList<>(result));
                manager.startAll();
                holograms.refreshAll();
                ready = true;
                getLogger().info("BravenSpawner ready — " + manager.size()
                        + " spawner(s), storage=" + settings.storageType
                        + (Schedulers.isFolia() ? ", folia" : ""));
            });
        });
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.stopAll();
        if (corruption != null) corruption.stop();
        if (wands != null) wands.shutdown();
        if (holograms != null) holograms.removeAll();
        if (checkpoints != null) {
            checkpoints.close();
            checkpoints = null;
        }
        if (audit != null) audit.close();
        if (storage != null && manager != null) {
            try {
                // Only a completed load may be written back: flushing before the
                // first load would replace the durable state with an empty map.
                if (ready) storage.flushAll(manager.all());
            } catch (RuntimeException e) {
                getLogger().severe("final flush failed: " + e.getMessage());
            }
            storage.close();
        }
        instance = null;
    }

    // ---------------------------------------------------------------- storage

    private void loadDatabases() {
        File file = new File(getDataFolder(), "databases.yml");
        if (!file.exists()) saveResource("databases.yml", false);
        databases = YamlConfiguration.loadConfiguration(file);
    }

    private Storage buildStorage() {
        String type = settings.storageType;
        if (type.equals("SQLITE") || type.equals("MYSQL")) {
            var section = databases.getConfigurationSection("mysql") != null ? databases : getConfig();
            var dialect = type.equals("MYSQL") ? SqlStorage.Dialect.MYSQL : SqlStorage.Dialect.SQLITE;
            return new SqlStorage(this, section, dialect);
        }
        return new YamlStorage(this);
    }

    // ------------------------------------------------------------- durability

    /** Marks a spawner for the next durable checkpoint. Cheap: it only dirties an id. */
    public void markDirty(Spawner spawner) {
        if (checkpoints != null && spawner != null) storage.upsert(spawner);
    }

    /** Marks a record for deletion on the next checkpoint. */
    public void markDeleted(UUID spawnerId) {
        if (checkpoints != null && spawnerId != null) storage.delete(spawnerId);
    }

    // ------------------------------------------------------------------ audit

    public void record(Spawner spawner, AuditEvent.Action action, Player actor, long amount, String detail) {
        if (audit == null) return;
        audit.record(AuditEvent.of(spawner, action,
                actor == null ? null : actor.getUniqueId(),
                actor == null ? "Console" : actor.getName(),
                amount, detail));
    }

    // ------------------------------------------------------------------ items

    /** Builds the physical spawner item, carrying kind, product and stack count. */
    public ItemStack createSpawnerItem(Spawner.Kind kind, String product, int stack) {
        ItemStack item = new ItemStack(settings.spawnerMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        ProductDefinition def = kind == Spawner.Kind.MOB
                ? (products == null ? null : products.mob(product))
                : (products == null ? null : products.item(product));
        String display = def == null ? product : def.displayName();
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
        return createSpawnerItem(spawner.kind(), spawner.product(), spawner.stack());
    }

    // ----------------------------------------------------------------- reload

    /** Persists, reloads config, and rebuilds every tick schedule. */
    public void reloadAll() {
        if (manager != null) storage.flushAll(manager.all());
        reloadConfig();
        loadDatabases();
        settings = Settings.load(getConfig(), databases, null);
        manager.settings(settings);
        if (products != null) products.reload();
        if (gui != null) gui.menus().reload();
        if (buyStack != null) buyStack.reload();
        if (auditMenu != null) auditMenu.reload();
        getLogger().info("configuration reloaded");
    }
}
