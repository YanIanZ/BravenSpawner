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
    private dev.yanianz.bravenspawner.particles.SpawnerParticles particles;
    private PlatformDetector platform;
    private Placeholders placeholders;
    private dev.yanianz.bravenspawner.commerce.ShopBridge shops;
    private dev.yanianz.bravenspawner.protection.ProtectionService protection;
    private dev.yanianz.bravenspawner.restrictions.RestrictedSpawners restrictions;
    private dev.yanianz.bravenspawner.events.SpawnerEvents events;
    private NamespacedKey spawnerIdKey, itemKindKey, itemProductKey, itemStackKey;
    private FileConfiguration databases;
    private volatile String storageFailure;
    private volatile boolean storageFallback;

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
    public dev.yanianz.bravenspawner.particles.SpawnerParticles particles() { return particles; }
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
        if (product == null || settings.unlimitedStorage) return 0;
        int scaled = scaledLimit(product.maxStoredItems(), spawner.stack());
        double tierScale = settings.capacityScale(spawner.tier());
        return scaled <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.floor(scaled * tierScale));
    }
    public int maximumXp(Spawner spawner) {
        ProductDefinition product = product(spawner);
        return product == null || settings.unlimitedStorage ? 0 : scaledLimit(product.maxStoredXp(), spawner.stack());
    }
    private static int scaledLimit(int limit, int stack) {
        return limit <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (long) limit * Math.max(1, stack));
    }
    /** Owner, trusted, admin, or anyone when {@code ownership.enabled: false}. */
    public boolean mayUse(Player player, Spawner spawner) {
        if (player == null || spawner == null) return false;
        return player.hasPermission("bravenspawner.admin") || !ownershipEnforced()
                || spawner.canUse(player.getUniqueId());
    }

    /** Owner or admin, or anyone when {@code ownership.enabled: false}. */
    public boolean mayBreak(Player player, Spawner spawner) {
        if (player == null || spawner == null) return false;
        return player.hasPermission("bravenspawner.admin") || !ownershipEnforced()
                || spawner.isOwner(player.getUniqueId());
    }

    private boolean ownershipEnforced() {
        return settings == null || settings.ownershipEnabled;
    }

    /** Logs at INFO with a [debug] prefix when {@code debug: true}. */
    public void debug(String message) {
        if (settings != null && settings.debug) getLogger().info("[debug] " + message);
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
        storage = openStorage();
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
        particles = new dev.yanianz.bravenspawner.particles.SpawnerParticles(this);
        gui = new SpawnerGui(this);
        getServer().getPluginManager().registerEvents(new BlockListener(this), this);
        getServer().getPluginManager().registerEvents(new EntityListener(this), this);
        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(new dev.yanianz.bravenspawner.listener.GuiGuardListener(this), this);
        wands = SellWandService.install(this);
        new CommandHandler(this).register(this);
        boosters.start(settings);
        corruption = new CorruptionTask(this);
        if (storage == null) {
            getLogger().severe("BravenSpawner is in STORAGE UNAVAILABLE mode: spawner placing, breaking, "
                    + "opening, selling and withdrawing are blocked until databases.yml is fixed and the "
                    + "server restarts. Cause: " + storageFailure);
            return;
        }
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
    /**
     * Opens the configured backend and fails closed.
     *
     * <p>A database that cannot be opened must never be replaced by {@code spawners.yml}
     * silently: that file predates the database, so loading it would hand out stale
     * loot and XP a second time, and spawners placed during the run would be orphaned
     * once the database is back. The default is therefore "storage unavailable" mode
     * (returns null, the plugin stays enabled but never becomes ready). Only an explicit
     * {@code databases.yml: fallback-to-yaml: true} opts into a fresh, empty
     * {@code spawners-fallback.yml}.
     */
    private Storage openStorage() {
        storageFailure = null;
        storageFallback = false;
        Storage configured = null;
        try {
            configured = buildStorage();
            configured.init();
            debug("storage opened: " + settings.storageType);
            return configured;
        } catch (Throwable error) {
            storageFailure = settings.storageType + ": " + error;
            getLogger().severe("storage init failed (" + settings.storageType + "): " + error);
            if (configured != null) {
                try { configured.close(); } catch (Throwable ignored) { /* release whatever init opened */ }
            }
        }
        if (databases != null && databases.getBoolean("fallback-to-yaml", false)) {
            try {
                Storage fallback = YamlStorage.freshFallback(this);
                fallback.init();
                storageFallback = true;
                getLogger().severe("fallback-to-yaml is enabled: this run uses an EMPTY "
                        + YamlStorage.FALLBACK_FILE + "; spawners stored in " + settings.storageType
                        + " are not loaded and spawners placed now will not be in the database later");
                return fallback;
            } catch (Throwable error) {
                storageFailure = storageFailure + "; YAML fallback failed: " + error;
                getLogger().severe("YAML fallback failed: " + error);
            }
        }
        return null;
    }

    /** False when no backend could be opened; every spawner interaction is refused in that mode. */
    public boolean isStorageAvailable() { return storage != null; }

    /** Why the configured backend failed to open this run, or null when it opened normally. */
    public String storageFailure() { return storageFailure; }

    /** True when this run explicitly fell back to an empty {@code spawners-fallback.yml}. */
    public boolean isStorageFallback() { return storageFallback; }

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
        // Only a head-shaped spawner item can carry a skin; the item material itself
        // stays spawner-item.material because placement recognises it by type.
        if (definition != null && meta instanceof org.bukkit.inventory.meta.SkullMeta skull
                && (definition.headMaterial() == null || definition.headMaterial() == org.bukkit.Material.PLAYER_HEAD)) {
            dev.yanianz.bravenspawner.util.HeadTextures.apply(skull, definition.headTexture());
        }
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
        dev.yanianz.bravenspawner.util.HeadTextures.clearCache();
        settings = Settings.load(getConfig(), databases, null, products);
        platform = new PlatformDetector(this);
        placeholders = new Placeholders();
        gui.reloadMenus();
        wands.reload();
        if (particles != null) particles.reload();
        boosters.reload(settings);
        restrictions.reload();
        events.reload();
        shops = new dev.yanianz.bravenspawner.commerce.ShopBridge(this);
        manager.settings(settings);
        holograms.reload();
    }
}
