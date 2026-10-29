package dev.yanianz.bravenspawner.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable snapshot of config.yml, built once at enable and again on reload.
 *
 * <p>Every field here exists because a switch in VirtualSpawner's config.yml needed
 * it; nothing is parsed lazily at runtime, so a region thread never touches the
 * live ConfigurationSection.
 */
public final class Settings {

    public final boolean useItemSpawners;
    public final boolean itemSpawnerOptimization;
    public final boolean instantPageNavigation;
    public final boolean newTickingSystem;
    public final boolean skipMenu;
    public final boolean bedrock;
    public final boolean enableLogger;
    public final boolean restoreMissingSpawners;

    public final long cooldownBreakMs;
    public final long cooldownStackMs;
    public final long cooldownGuiMs;
    public final long guiReconnectCooldownMs;
    public final long guiLockTimeoutMs;
    public final double guiMaxTeleportDistance;

    public final List<String> blacklistedWorlds;
    public final boolean noActivationRange;
    public final int activationRadius;

    public final boolean blockCollectionInInventory;
    public final boolean unlimitedStorage;
    public final int spawnerLimitInChunk;
    public final boolean convertSpawners;
    public final boolean fullStack;
    public final boolean allowHoppers;
    public final boolean enableMending;
    public final boolean dropXpAsOrbs;
    public final boolean placeholdersInGui;
    public final boolean removeDurabilityOnBreak;
    public final boolean silkTouchOnly;
    public final boolean disableExplosionDamage;
    public final boolean multipleDrop;
    public final int stackLimit;

    public final boolean enableBoosters;
    public final long boosterSeconds;
    public final java.util.Map<String, Double> boosterItems;
    public final boolean disableAutosellNotifications;
    public final boolean disableItemStock;
    public final boolean disableSellFeature;

    public final boolean ownershipEnabled;
    public final boolean corruptionEnabled;
    public final boolean corruptionAllowBreaking;
    public final int corruptionSolveRadius;
    public final int corruptionDelayMin;
    public final int corruptionDelayMax;

    public final boolean ninjaEnabled;
    public final int ninjaStartDistance;
    public final int ninjaVisibilityDistance;

    public final Material spawnerMaterial;
    public final String spawnerName;
    public final List<String> spawnerLore;

    public final boolean moneyEnabled;
    public final boolean moneyApplyOnAmount;
    public final List<String> moneyFormats;

    public final String storageType;
    public final int storageThreadPool;
    public final int storageBatchSize;
    public final EconomySection economy;
    public final Map<String, Product> mobs;
    public final Map<String, Product> items;
    public final Map<String, Double> sellPrices;
    public final List<Tier> tiers;

    private Settings(Builder b) {
        this.useItemSpawners = b.useItemSpawners;
        this.itemSpawnerOptimization = b.itemSpawnerOptimization;
        this.instantPageNavigation = b.instantPageNavigation;
        this.newTickingSystem = b.newTickingSystem;
        this.skipMenu = b.skipMenu;
        this.bedrock = b.bedrock;
        this.enableLogger = b.enableLogger;
        this.restoreMissingSpawners = b.restoreMissingSpawners;
        this.cooldownBreakMs = b.cooldownBreakMs;
        this.cooldownStackMs = b.cooldownStackMs;
        this.cooldownGuiMs = b.cooldownGuiMs;
        this.guiReconnectCooldownMs = b.guiReconnectCooldownMs;
        this.guiLockTimeoutMs = b.guiLockTimeoutMs;
        this.guiMaxTeleportDistance = b.guiMaxTeleportDistance;
        this.blacklistedWorlds = List.copyOf(b.blacklistedWorlds);
        this.noActivationRange = b.noActivationRange;
        this.activationRadius = b.activationRadius;
        this.blockCollectionInInventory = b.blockCollectionInInventory;
        this.unlimitedStorage = b.unlimitedStorage;
        this.spawnerLimitInChunk = b.spawnerLimitInChunk;
        this.convertSpawners = b.convertSpawners;
        this.fullStack = b.fullStack;
        this.allowHoppers = b.allowHoppers;
        this.enableMending = b.enableMending;
        this.dropXpAsOrbs = b.dropXpAsOrbs;
        this.placeholdersInGui = b.placeholdersInGui;
        this.removeDurabilityOnBreak = b.removeDurabilityOnBreak;
        this.silkTouchOnly = b.silkTouchOnly;
        this.disableExplosionDamage = b.disableExplosionDamage;
        this.multipleDrop = b.multipleDrop;
        this.stackLimit = b.stackLimit;
        this.enableBoosters = b.enableBoosters;
        this.boosterSeconds = b.boosterSeconds;
        this.boosterItems = Map.copyOf(b.boosterItems);
        this.disableAutosellNotifications = b.disableAutosellNotifications;
        this.disableItemStock = b.disableItemStock;
        this.disableSellFeature = b.disableSellFeature;
        this.ownershipEnabled = b.ownershipEnabled;
        this.corruptionEnabled = b.corruptionEnabled;
        this.corruptionAllowBreaking = b.corruptionAllowBreaking;
        this.corruptionSolveRadius = b.corruptionSolveRadius;
        this.corruptionDelayMin = b.corruptionDelayMin;
        this.corruptionDelayMax = b.corruptionDelayMax;
        this.ninjaEnabled = b.ninjaEnabled;
        this.ninjaStartDistance = b.ninjaStartDistance;
        this.ninjaVisibilityDistance = b.ninjaVisibilityDistance;
        this.spawnerMaterial = b.spawnerMaterial;
        this.spawnerName = b.spawnerName;
        this.spawnerLore = List.copyOf(b.spawnerLore);
        this.moneyEnabled = b.moneyEnabled;
        this.moneyApplyOnAmount = b.moneyApplyOnAmount;
        this.moneyFormats = List.copyOf(b.moneyFormats);
        this.storageType = b.storageType;
        this.storageThreadPool = b.storageThreadPool;
        this.storageBatchSize = b.storageBatchSize;
        this.economy = b.economy;
        this.mobs = Map.copyOf(b.mobs);
        this.items = Map.copyOf(b.items);
        this.sellPrices = Map.copyOf(b.sellPrices);
        this.tiers = List.copyOf(b.tiers);
    }

    public static Settings load(ConfigurationSection root, ConfigurationSection databases, ConfigurationSection hooks) {
        Builder b = new Builder();
        b.useItemSpawners = root.getBoolean("use_item_spawners", false);
        b.itemSpawnerOptimization = root.getBoolean("item_spawner_optimization", false);
        b.instantPageNavigation = root.getBoolean("instant_page_navigation", true);
        b.newTickingSystem = root.getBoolean("use_new_ticking_system", true);
        b.skipMenu = root.getBoolean("skip-menu", false);
        b.bedrock = root.getBoolean("bedrock", false);
        b.enableLogger = root.getBoolean("enable-logger", false);
        b.restoreMissingSpawners = root.getBoolean("restore-missing-spawners", false);

        ConfigurationSection cd = root.getConfigurationSection("cooldowns");
        b.cooldownBreakMs = cd == null ? 300 : cd.getLong("break", 300);
        b.cooldownStackMs = cd == null ? 150 : cd.getLong("stack", 150);
        b.cooldownGuiMs = cd == null ? 1000 : cd.getLong("gui_open", 1000);

        ConfigurationSection gui = root.getConfigurationSection("chest_gui.protection");
        b.guiReconnectCooldownMs = gui == null ? 3000 : gui.getLong("reconnect_cooldown", 3000);
        b.guiLockTimeoutMs = gui == null ? 5000 : gui.getLong("spawner_lock_timeout", 5000);
        b.guiMaxTeleportDistance = gui == null ? 100.0 : gui.getDouble("max_teleport_distance", 100.0);

        b.blacklistedWorlds = new ArrayList<>(root.getStringList("blacklisted-worlds"));
        b.noActivationRange = root.getBoolean("no-activation-range", false);
        b.activationRadius = root.getInt("activation-radius", 10);

        b.blockCollectionInInventory = root.getBoolean("block-collection-in-inventory", false);
        b.unlimitedStorage = root.getBoolean("unlimited-storage", false);
        b.spawnerLimitInChunk = root.getInt("spawner-limit-in-chunk", 100);
        b.convertSpawners = root.getBoolean("convert-spawners", false);
        b.fullStack = root.getBoolean("full-stack", false);
        b.allowHoppers = root.getBoolean("allow-hoppers", false);
        b.enableMending = root.getBoolean("enable-mending", false);
        b.dropXpAsOrbs = root.getBoolean("drop-xp-as-orbs", false);
        b.placeholdersInGui = root.getBoolean("placeholders-in-gui", false);
        b.removeDurabilityOnBreak = root.getBoolean("remove-durability-on-break", false);
        b.silkTouchOnly = root.getBoolean("silk-touch-only", true);
        b.disableExplosionDamage = root.getBoolean("disable-explosion-damage-spawner", true);
        b.multipleDrop = root.getBoolean("multiple-drop", false);
        b.stackLimit = Math.max(1, root.getInt("stack-limit", 200000));

        b.enableBoosters = root.getBoolean("enable-boosters", true);
        b.boosterSeconds = root.getLong("boosters.duration-seconds", 600L);
        ConfigurationSection boostItems = root.getConfigurationSection("boosters.items");
        if (boostItems != null) {
            for (String key : boostItems.getKeys(false)) {
                b.boosterItems.put(key.toUpperCase(Locale.ROOT), boostItems.getDouble(key));
            }
        }
        b.disableAutosellNotifications = root.getBoolean("disable-autosell-notifications", false);
        b.disableItemStock = root.getBoolean("disable-item-stock", false);
        b.disableSellFeature = root.getBoolean("disable-sell-feature", false);

        b.ownershipEnabled = root.getBoolean("ownership.enabled", false);
        b.corruptionEnabled = root.getBoolean("corruption.enabled", false);
        b.corruptionAllowBreaking = root.getBoolean("corruption.allow-breaking", false);
        b.corruptionSolveRadius = root.getInt("corruption.solve-radius", 5);
        b.corruptionDelayMin = root.getInt("corruption.delay.min", 3600);
        b.corruptionDelayMax = root.getInt("corruption.delay.max", 21600);

        b.ninjaEnabled = root.getBoolean("ninja-spawners.enable-invisibility", false);
        b.ninjaStartDistance = root.getInt("ninja-spawners.start-invisibility-distance", 128);
        b.ninjaVisibilityDistance = root.getInt("ninja-spawners.visibility-distance", 15);

        Material material = Material.matchMaterial(root.getString("spawner-item.material", "SPAWNER"));
        b.spawnerMaterial = material == null ? Material.SPAWNER : material;
        b.spawnerName = root.getString("spawner-item.name", "%color%&l%displayname%'s Spawner");
        b.spawnerLore = new ArrayList<>(root.getStringList("spawner-item.lore"));

        b.moneyEnabled = root.getBoolean("money-decimals.enabled", true);
        b.moneyApplyOnAmount = root.getBoolean("money-decimals.apply-on-amount", true);
        ConfigurationSection formats = root.getConfigurationSection("money-decimals.formats");
        List<String> moneyFormats = new ArrayList<>();
        if (formats != null) {
            for (int i = 1; i <= 6; i++) {
                moneyFormats.add(formats.getString(String.valueOf(i), "%number%" + suffix(i)));
            }
        }
        b.moneyFormats = moneyFormats;

        b.storageType = databases == null ? "YAML"
                : databases.getString("type", "sqlite").toUpperCase(Locale.ROOT);
        b.storageThreadPool = databases == null ? 5 : databases.getInt("thread_pool_size", 5);
        b.storageBatchSize = databases == null ? 300 : databases.getInt("batch_size", 300);

        b.economy = EconomySection.from(root.getConfigurationSection("economy"));

        b.mobs = new LinkedHashMap<>();
        parseProducts(root.getConfigurationSection("mobs"), b.mobs);
        b.items = new LinkedHashMap<>();
        parseProducts(root.getConfigurationSection("items"), b.items);

        b.sellPrices = new LinkedHashMap<>();
        ConfigurationSection prices = root.getConfigurationSection("sell-prices");
        if (prices != null) {
            for (String key : prices.getKeys(false)) {
                b.sellPrices.put(key.toUpperCase(Locale.ROOT), prices.getDouble(key));
            }
        }

        List<Tier> tiers = new ArrayList<>();
        for (Map<?, ?> raw : root.getMapList("upgrades.tiers")) {
            Object name = raw.get("name");
            if (name == null) continue;
            tiers.add(new Tier(
                    String.valueOf(name),
                    raw.get("cost") instanceof Number n ? n.doubleValue() : 0.0,
                    raw.get("interval-ticks") instanceof Number n ? n.longValue() : 1200L,
                    raw.get("capacity") instanceof Number n ? n.intValue() : 45));
        }
        if (tiers.isEmpty()) tiers.add(new Tier("base", 0.0, 1200L, 45));
        b.tiers = tiers;

        return new Settings(b);
    }

    private static String suffix(int power) {
        return switch (power) {
            case 1 -> "K";
            case 2 -> "M";
            case 3 -> "B";
            case 4 -> "T";
            case 5 -> "Q";
            default -> "Qt";
        };
    }

    private static void parseProducts(ConfigurationSection section, Map<String, Product> target) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) continue;
            List<Drop> drops = new ArrayList<>();
            for (String raw : s.getStringList("drops")) {
                Drop d = Drop.parse(raw);
                if (d != null) drops.add(d);
            }
            target.put(key.toUpperCase(Locale.ROOT), new Product(
                    key.toUpperCase(Locale.ROOT),
                    s.getString("display", key),
                    drops,
                    s.getInt("xp", 0)));
        }
    }

    public java.util.Map<String, Product> mobs() { return mobs; }

    public java.util.Map<String, Product> items() { return items; }

    public long boosterSeconds() { return 600L; }

    /** Multiplier for a booster item, or null when the material is not one. */
    public Double boosterItem(org.bukkit.Material material) {
        if (material == null || boosterItems == null) return null;
        return boosterItems.get(material.name());
    }

    public Product mob(String id) {
        return id == null ? null : mobs.get(id.toUpperCase(Locale.ROOT));
    }

    public Product item(String id) {
        return id == null ? null : items.get(id.toUpperCase(Locale.ROOT));
    }

    public boolean isBlacklisted(String world) {
        return blacklistedWorlds.stream().anyMatch(w -> w.equalsIgnoreCase(world));
    }

    // ---------------------------------------------------------------- records

    public static final class Product {
        public final String id;
        public final String display;
        public final List<Drop> drops;
        public final int xp;

        public Product(String id, String display, List<Drop> drops, int xp) {
            this.id = id;
            this.display = display;
            this.drops = List.copyOf(drops);
            this.xp = xp;
        }
    }

    public static final class Drop {
        public final Material material;
        public final int amount;
        public final double chance;

        public Drop(Material material, int amount, double chance) {
            this.material = material;
            this.amount = amount;
            this.chance = chance;
        }

        public static Drop parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String[] parts = raw.split(":");
            Material material = Material.matchMaterial(parts[0]);
            if (material == null) return null;
            int amount = 1;
            double chance = 1.0;
            if (parts.length > 1) {
                try {
                    amount = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
            if (parts.length > 2) {
                try {
                    chance = Double.parseDouble(parts[2].trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return new Drop(material, Math.max(1, amount), Math.min(1.0, Math.max(0.0, chance)));
        }

        public int roll(java.util.Random random) {
            if (chance >= 1.0) return amount;
            if (chance <= 0.0) return 0;
            int n = 0;
            for (int i = 0; i < amount; i++) if (random.nextDouble() < chance) n++;
            return n;
        }
    }

    public static final class Tier {
        public final String name;
        public final double cost;
        public final long intervalTicks;
        public final int capacity;

        public Tier(String name, double cost, long intervalTicks, int capacity) {
            this.name = name;
            this.cost = cost;
            this.intervalTicks = intervalTicks;
            this.capacity = capacity;
        }
    }

    public static final class EconomySection {
        public final boolean enabled;
        public final double purchasePrice;
        public final double sellMultiplier;
        public final boolean autosell;

        private EconomySection(boolean enabled, double purchasePrice, double sellMultiplier, boolean autosell) {
            this.enabled = enabled;
            this.purchasePrice = purchasePrice;
            this.sellMultiplier = sellMultiplier;
            this.autosell = autosell;
        }

        static EconomySection from(ConfigurationSection s) {
            if (s == null) return new EconomySection(false, 0, 1.0, false);
            return new EconomySection(
                    s.getBoolean("enabled", false),
                    s.getDouble("purchase-price", 0.0),
                    s.getDouble("sell-multiplier", 1.0),
                    s.getBoolean("autosell", false));
        }
    }

    private static final class Builder {
        boolean useItemSpawners, itemSpawnerOptimization, instantPageNavigation, newTickingSystem,
                skipMenu, bedrock, enableLogger, restoreMissingSpawners;
        long cooldownBreakMs, cooldownStackMs, cooldownGuiMs, guiReconnectCooldownMs, guiLockTimeoutMs;
        double guiMaxTeleportDistance;
        List<String> blacklistedWorlds;
        boolean noActivationRange;
        int activationRadius;
        long boosterSeconds;
        Map<String, Double> boosterItems = new LinkedHashMap<>();
        boolean blockCollectionInInventory, unlimitedStorage, convertSpawners, fullStack, allowHoppers,
                enableMending, dropXpAsOrbs, placeholdersInGui, removeDurabilityOnBreak, silkTouchOnly,
                disableExplosionDamage, multipleDrop, enableBoosters, disableAutosellNotifications,
                disableItemStock, disableSellFeature, ownershipEnabled, corruptionEnabled,
                corruptionAllowBreaking, ninjaEnabled, moneyEnabled, moneyApplyOnAmount;
        int spawnerLimitInChunk, stackLimit, corruptionSolveRadius, corruptionDelayMin, corruptionDelayMax,
                ninjaStartDistance, ninjaVisibilityDistance, storageThreadPool, storageBatchSize;
        Material spawnerMaterial;
        String spawnerName, storageType;
        List<String> spawnerLore, moneyFormats;
        EconomySection economy;
        Map<String, Product> mobs, items;
        Map<String, Double> sellPrices;
        List<Tier> tiers;
    }
}
