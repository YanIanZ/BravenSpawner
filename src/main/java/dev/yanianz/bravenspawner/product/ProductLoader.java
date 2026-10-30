package dev.yanianz.bravenspawner.product;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the per-product catalogue from {@code plugins/BravenSpawner/mobs/*.yml}
 * and {@code .../itemspawners/*.yml}.
 *
 * <p>Each file is one product and the file name is its id. Loading is failure
 * tolerant on purpose: a single broken file must not take the other forty with
 * it, so every parse error is logged as a SEVERE line that names the file and the
 * reason, and the scan continues. The alternative — aborting the load — turns one
 * typo into an empty shop on a live server.
 *
 * <p>First boot lays down the packaged defaults through
 * {@link JavaPlugin#saveResource(String, boolean)} with {@code replace=false}, so
 * an existing edited file is never overwritten. Migration runs first
 * ({@link ProductMigration}); when it produced files from the legacy config, the
 * packaged defaults are skipped for that directory so the server keeps exactly the
 * catalogue it had.
 *
 * <p>The returned maps are owned by the caller and never mutated afterwards; the
 * registry copies them into an immutable snapshot.
 */
public final class ProductLoader {

    /** The catalogue shipped up to 1.2.1; a marker without an id list was written for exactly these. */
    private static final List<String> LEGACY_DEFAULT_MOBS = List.of(
            "allay", "axolotl", "bat", "blaze", "camel", "cat",
            "cave_spider", "chicken", "cod", "cow", "creeper", "enderman",
            "frog", "glow_squid", "goat", "guardian", "hoglin", "horse",
            "husk", "iron_golem", "magma_cube", "mushroom_cow", "parrot", "phantom",
            "pig", "piglin", "piglin_brute", "rabbit", "sheep", "skeleton",
            "slime", "sniffer", "spider", "squid", "strider", "witch",
            "wither_skeleton", "wolf", "zombie", "zombified_piglin");

    /**
     * Products shipped inside the jar; keep in sync with {@code resources/mobs}.
     * Types newer than the running server are skipped at load, not rejected.
     */
    static final List<String> DEFAULT_MOBS = concat(LEGACY_DEFAULT_MOBS, List.of(
            "armadillo", "bee", "bogged", "breeze", "camel_husk", "copper_golem",
            "creaking", "dolphin", "donkey", "drowned", "elder_guardian", "ender_dragon",
            "endermite", "evoker", "fox", "ghast", "giant", "happy_ghast",
            "illusioner", "llama", "mule", "nautilus", "ocelot", "panda",
            "parched", "pillager", "polar_bear", "pufferfish", "ravager", "salmon",
            "shulker", "silverfish", "skeleton_horse", "snow_golem", "stray", "sulfur_cube",
            "tadpole", "trader_llama", "tropical_fish", "turtle", "vex", "villager",
            "vindicator", "wandering_trader", "warden", "wither", "zoglin", "zombie_horse",
            "zombie_nautilus", "zombie_villager"));

    private static final String DEFAULTS_MARKER = ".migrated";
    private static final String DEFAULTS_HEADER = "catalogue initialized";

    /** Products shipped inside the jar; keep in sync with {@code resources/itemspawners}. */
    private static final List<String> DEFAULT_ITEMS = List.of(
            "cobble", "wheat", "diamond", "iron");

    /** One second; below this a spawner would run every tick and melt the scheduler. */
    private static final int MIN_INTERVAL_TICKS = 20;

    /** One hour; above this a product is effectively parked, which hides typos. */
    private static final int MAX_INTERVAL_TICKS = 72_000;

    private static final int MAX_XP = 1_000_000;
    private static final int MAX_DROP_AMOUNT = 6_400;

    private final JavaPlugin plugin;
    private final boolean migrateLegacy;

    /** Per-load summaries, logged once each instead of one line per file. */
    private final List<String> unavailable = new ArrayList<>();
    private final List<String> disabled = new ArrayList<>();
    private final List<String> unknownDropMaterials = new ArrayList<>();

    public ProductLoader(JavaPlugin plugin) { this(plugin, true); }

    public ProductLoader(JavaPlugin plugin, boolean migrateLegacy) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.migrateLegacy = migrateLegacy;
    }

    /**
     * Scans both product directories.
     *
     * <p>Order matters and is fixed here: create directories, migrate the legacy
     * config while the directories are still empty, lay down packaged defaults for
     * directories that were not migrated, then parse. Doing defaults before
     * migration would fill the directory and permanently suppress the migration.
     */
    public LoadResult load() {
        File mobsDir = new File(plugin.getDataFolder(), "mobs");
        File itemsDir = new File(plugin.getDataFolder(), "itemspawners");
        ensureDirectory(mobsDir);
        ensureDirectory(itemsDir);

        ProductMigration migration = new ProductMigration(plugin);
        boolean mobsMigrated = migrateLegacy && migration.migrateIfEmpty(mobsDir, "mobs", ProductDefinition.Kind.MOB);
        boolean itemsMigrated = migrateLegacy && migration.migrateIfEmpty(itemsDir, "items", ProductDefinition.Kind.ITEM);

        if (!mobsMigrated && firstCatalogue(mobsDir)) saveDefaults(mobsDir, "mobs", DEFAULT_MOBS, List.of());
        else if (!mobsMigrated) offerNewDefaults(mobsDir, "mobs", DEFAULT_MOBS, LEGACY_DEFAULT_MOBS);
        if (!itemsMigrated && firstCatalogue(itemsDir)) saveDefaults(itemsDir, "itemspawners", DEFAULT_ITEMS, List.of());

        unavailable.clear();
        disabled.clear();
        unknownDropMaterials.clear();
        Map<String, ProductDefinition> mobs = new LinkedHashMap<>();
        int malformed = parseDirectory(mobsDir, ProductDefinition.Kind.MOB, mobs);
        Map<String, ProductDefinition> items = new LinkedHashMap<>();
        malformed += parseDirectory(itemsDir, ProductDefinition.Kind.ITEM, items);

        plugin.getLogger().info("Loaded " + mobs.size() + " mob product(s) and "
                + items.size() + " item product(s)"
                + (malformed > 0 ? "; " + malformed + " malformed file(s) skipped" : ""));
        if (!unavailable.isEmpty()) {
            plugin.getLogger().info("Skipped " + unavailable.size()
                    + " product(s) whose type is not available on this server version: "
                    + String.join(", ", unavailable));
        }
        if (!disabled.isEmpty()) {
            plugin.getLogger().info(disabled.size() + " product(s) disabled (enabled: false): "
                    + String.join(", ", disabled));
        }
        if (!unknownDropMaterials.isEmpty()) {
            plugin.getLogger().warning("Skipped " + unknownDropMaterials.size()
                    + " drop(s) with a material unknown on this server: "
                    + String.join(", ", unknownDropMaterials));
        }
        return new LoadResult(mobs, items, malformed, unavailable, disabled);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> out = new ArrayList<>(first);
        out.addAll(second);
        return List.copyOf(out);
    }

    private static boolean firstCatalogue(File directory) {
        if (new File(directory, ".migrated").exists()) return false;
        String[] files = directory.list((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        return files != null && files.length == 0;
    }

    private void ensureDirectory(File directory) {
        if (directory.isDirectory()) return;
        if (!directory.mkdirs() && !directory.isDirectory()) {
            plugin.getLogger().severe("could not create product directory " + directory.getPath());
        }
    }

    /**
     * Copies packaged defaults into a directory without touching existing files.
     * The existence check avoids {@code saveResource}'s warning for every file on
     * every boot; {@code replace=false} remains the safety net.
     */
    private void saveDefaults(File directory, String resourceDirectory, List<String> ids, List<String> alreadyOffered) {
        for (String id : ids) {
            if (alreadyOffered.contains(id)) continue;
            File target = new File(directory, id + ".yml");
            if (target.isFile()) continue;
            String resource = resourceDirectory + '/' + id + ".yml";
            try {
                plugin.saveResource(resource, false);
            } catch (RuntimeException e) {
                plugin.getLogger().severe("could not create default product " + resource
                        + ": " + e.getMessage());
            }
        }
        String[] present = directory.list((dir, name) -> name.endsWith(".yml"));
        if (present != null && present.length > 0) {
            // The id list records which defaults were offered, so a later release
            // can add new products without resurrecting files the owner deleted.
            String body = DEFAULTS_HEADER + "\n" + String.join("\n", ids) + "\n";
            try { java.nio.file.Files.writeString(new File(directory, DEFAULTS_MARKER).toPath(), body, java.nio.charset.StandardCharsets.UTF_8); }
            catch (IOException error) { plugin.getLogger().warning("Could not mark catalogue initialized: " + error.getMessage()); }
        }
    }

    /**
     * Lays down bundled products added after the catalogue was initialized.
     * Only directories initialized from the defaults are touched; a directory
     * produced by legacy migration (empty marker) keeps exactly what it had.
     */
    private void offerNewDefaults(File directory, String resourceDirectory, List<String> ids, List<String> legacy) {
        File marker = new File(directory, DEFAULTS_MARKER);
        if (!marker.isFile()) return;
        List<String> lines;
        try { lines = java.nio.file.Files.readAllLines(marker.toPath(), java.nio.charset.StandardCharsets.UTF_8); }
        catch (IOException error) { return; }
        if (lines.isEmpty() || !lines.getFirst().trim().equals(DEFAULTS_HEADER)) return;
        List<String> offered = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) if (!line.isBlank()) offered.add(line.trim());
        if (offered.isEmpty()) offered.addAll(legacy);
        if (offered.containsAll(ids)) return;
        List<String> added = new ArrayList<>();
        for (String id : ids) if (!offered.contains(id) && !new File(directory, id + ".yml").exists()) added.add(id);
        saveDefaults(directory, resourceDirectory, ids, offered);
        if (!added.isEmpty()) {
            plugin.getLogger().info("Added " + added.size() + " new bundled product(s) to "
                    + directory.getName() + "/: " + String.join(", ", added));
        }
    }

    /**
     * Parses every {@code .yml} file in one directory, sorted for deterministic
     * registry order. Returns the number of files that failed to parse.
     */
    private int parseDirectory(File directory, ProductDefinition.Kind kind,
                               Map<String, ProductDefinition> target) {
        File[] files = directory.listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            plugin.getLogger().severe("could not list product directory " + directory.getPath());
            return 0;
        }
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        int malformed = 0;
        for (File file : files) {
            String id = fileId(file);
            try {
                ProductDefinition definition = parse(file, id, kind);
                target.put(definition.id(), definition);
            } catch (InvalidConfigurationException | IOException e) {
                malformed++;
                plugin.getLogger().severe(label(directory, file) + ": " + e.getMessage());
            } catch (SkippedProductException e) {
                (e.disabled ? disabled : unavailable).add(id);
            } catch (MalformedProductException e) {
                malformed++;
                plugin.getLogger().severe(label(directory, file) + ": " + e.getMessage()
                        + " — product skipped");
            } catch (RuntimeException e) {
                // A malformed value can surface as a class-cast or NPE deep in the
                // Bukkit configuration reader; one bad file still must not stop the
                // remaining products from loading.
                malformed++;
                plugin.getLogger().severe(label(directory, file) + ": unexpected parse failure ("
                        + e.getClass().getSimpleName() + ": " + e.getMessage() + ") — product skipped");
            }
        }
        return malformed;
    }

    private ProductDefinition parse(File file, String id, ProductDefinition.Kind kind)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);

        if (!yaml.getBoolean("enabled", true)) throw new SkippedProductException(true);

        ProductDefinition.Builder builder = ProductDefinition.builder(kind, id);

        // The original ships `type:` and older BravenSpawner builds wrote
        // `entity-type:`; both names must resolve to the same field or a migrated
        // server silently loses its whole catalogue.
        String typeName = kind == ProductDefinition.Kind.MOB
                ? firstString(yaml, "entity-type", "type", "entity")
                : firstString(yaml, "item-type", "type", "material");
        if (typeName == null || typeName.isBlank()) typeName = id;
        if (typeName == null || typeName.isBlank()) {
            throw new MalformedProductException("missing " + typeKey(kind));
        }

        if (kind == ProductDefinition.Kind.MOB) {
            EntityType type = EntityTypes.entity(typeName);
            if (type == null) {
                if (EntityTypes.entityCandidates(typeName).isEmpty()) {
                    throw new MalformedProductException("invalid entity-type '" + typeName + "'");
                }
                // Unknown here usually means newer than this server (e.g. a 26.x mob
                // on 1.21.1); summarised in one line rather than one error per file.
                throw new SkippedProductException(false);
            }
            builder.entityType(type);
        } else {
            Material material = material(typeName);
            if (material == null) {
                throw new MalformedProductException("unknown or non-item item-type '"
                        + typeName + "'");
            }
            builder.outputMaterial(material);
        }

        String display = displayName(yaml);
        if (display != null) builder.displayName(display);

        builder.intervalTicks(intervalTicks(yaml));
        int xp = clamp(yaml.getInt("xp.min", yaml.getInt("xp", 0)), 0, MAX_XP);
        builder.xp(xp);
        builder.ranges(clamp(yaml.getInt("spawn-delays.max", 0) * 20,
                intervalTicks(yaml), MAX_INTERVAL_TICKS),
                clamp(yaml.getInt("xp.max", xp), xp, MAX_XP));
        builder.mustBeFullToCollect(yaml.getBoolean("storage.must-be-full-to-collect", false));
        builder.limits(yaml.getInt("storage.max-items", 0), yaml.getInt("storage.max-xp", 0),
                yaml.getInt("max-stack", 0));

        int unknownBefore = unknownDropMaterials.size();
        List<ProductDefinition.Drop> drops = drops(yaml, id);
        if (drops.isEmpty()) {
            if (unknownDropMaterials.size() > unknownBefore) throw new SkippedProductException(false);
            throw new MalformedProductException("no usable drops");
        }
        for (ProductDefinition.Drop drop : drops) builder.addDrop(drop);

        boolean stockEnabled = yaml.getBoolean("stock.enabled", false);
        int maxStock = clamp(yaml.getInt("stock.max-stock", 1000), 0, Stock.MAX_UNITS);
        int restock = clamp(yaml.getInt("stock.restock-per-cycle", 8), 0, Stock.MAX_UNITS);
        builder.stock(stockEnabled, maxStock, restock);

        builder.spawnerItemName(firstString(yaml, "spawner-item-name", "spawner-item.name"));
        builder.spawnerItemLore(spawnerLore(yaml));
        builder.spawnerPrice(Math.max(0.0, yaml.getDouble("spawner-price", 0.0)));
        builder.head(material(yaml.getString("head.material")),
                firstString(yaml, "head.head-texture", "head.texture", "head-texture"));

        return builder.build();
    }

    /**
     * Interval resolution, in precedence order: explicit ticks, the original
     * {@code spawn-delays.min} seconds form, then the historical 1200-tick default.
     * Both forms exist because the packaged files use ticks while server owners
     * migrating from the original plugin expect seconds.
     */
    private static int intervalTicks(YamlConfiguration yaml) {
        int ticks = yaml.getInt("interval-ticks", 0);
        if (ticks <= 0) ticks = yaml.getInt("spawn-delays.min", 0) * 20;
        if (ticks <= 0) ticks = 1200;
        return clamp(ticks, MIN_INTERVAL_TICKS, MAX_INTERVAL_TICKS);
    }

    private static String displayName(YamlConfiguration yaml) {
        String value = firstString(yaml, "display-name", "display");
        if (value == null || value.isBlank() || value.equalsIgnoreCase("none")) return null;
        return value;
    }

    /**
     * Reads both drop layouts: the packaged numbered map
     * ({@code drops: {1: {material, amount: {min, max}}}}) and the flat list used
     * by migrated configs and by simple hand-written files.
     */
    private List<ProductDefinition.Drop> drops(YamlConfiguration yaml, String id) {
        Object raw = yaml.get("drops");
        List<ProductDefinition.Drop> out = new ArrayList<>();
        if (raw instanceof ConfigurationSection section) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry != null) addDrop(out, entry.getValues(false), id);
            }
        } else if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof ConfigurationSection section) {
                    addDrop(out, section.getValues(false), id);
                } else if (element instanceof Map<?, ?> map) {
                    addDrop(out, map, id);
                } else if (element instanceof String text) {
                    ProductDefinition.Drop drop = legacyDrop(text);
                    if (drop != null) out.add(drop);
                    else plugin.getLogger().warning("product " + id
                            + ": unreadable drop '" + text + "' — drop skipped");
                }
            }
        }
        return out;
    }

    private void addDrop(List<ProductDefinition.Drop> target, Map<?, ?> map, String id) {
        Material material = material(map.get("material"));
        if (material == null) {
            unknownDropMaterials.add(id + ":" + map.get("material"));
            return;
        }

        int min = 1;
        int max = 1;
        Object amount = map.get("amount");
        if (amount instanceof ConfigurationSection range) {
            min = number(range.get("min"), 1);
            max = number(range.get("max"), min);
        } else if (amount instanceof Map<?, ?> range) {
            min = number(range.get("min"), 1);
            max = number(range.get("max"), min);
        } else if (amount instanceof Number number) {
            min = max = number.intValue();
        }
        min = clamp(min, 1, MAX_DROP_AMOUNT);
        max = clamp(max, min, MAX_DROP_AMOUNT);

        double chance = probability(map.get("chance"));
        if (chance <= 0.0) {
            plugin.getLogger().warning("product " + id + ": drop " + material.name()
                    + " has a zero chance — drop skipped");
            return;
        }
        double price = map.get("sell-price") instanceof Number n && Double.isFinite(n.doubleValue())
                ? Math.max(-1.0, n.doubleValue()) : -1.0;
        String display = map.get("display-name") instanceof String text && !text.equalsIgnoreCase("none") ? text : null;
        int model = map.get("custom-model-data") instanceof Number n ? n.intValue() : -1;
        target.add(new ProductDefinition.Drop(material, min, max, chance, price, display, model));
    }

    /** Structured drop chances are always percentages, including values below one. */
    private static double probability(Object raw) {
        double value = raw instanceof Number number ? number.doubleValue() : 100.0;
        return Double.isFinite(value) ? Math.min(1.0, Math.max(0.0, value / 100.0)) : 0.0;
    }

    private static ProductDefinition.Drop legacyDrop(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(":");
        Material material = material(parts[0]);
        if (material == null) return null;
        int amount = number(parts.length > 1 ? parts[1] : null, 1);
        amount = clamp(amount, 1, MAX_DROP_AMOUNT);
        double chance = 1.0;
        if (parts.length > 2) {
            try { chance = Double.parseDouble(parts[2]); }
            catch (NumberFormatException invalid) { return null; }
        }
        if (!Double.isFinite(chance)) return null;
        chance = Math.min(1.0, Math.max(0.0, chance));
        if (chance <= 0.0) return null;
        return new ProductDefinition.Drop(material, amount, amount, chance);
    }

    private static Material material(Object raw) {
        return raw == null ? null : EntityTypes.material(String.valueOf(raw));
    }

    /** Returns the first non-blank value among the given keys. */
    private static String firstString(YamlConfiguration yaml, String... keys) {
        for (String key : keys) {
            String value = yaml.getString(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    @Deprecated
    private static String firstStringLegacy(YamlConfiguration yaml, String primary, String secondary) {
        String value = yaml.getString(primary);
        return value != null ? value : yaml.getString(secondary);
    }

    private static List<String> spawnerLore(YamlConfiguration yaml) {
        if (yaml.isList("spawner-item-lore")) return yaml.getStringList("spawner-item-lore");
        if (yaml.isList("spawner-item.lore")) return yaml.getStringList("spawner-item.lore");
        return null;
    }

    private static int number(Object raw, int fallback) {
        if (raw instanceof Number number) return number.intValue();
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                // fall through to the fallback
            }
        }
        return fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String typeKey(ProductDefinition.Kind kind) {
        return kind == ProductDefinition.Kind.MOB ? "entity-type" : "item-type";
    }

    private static String fileId(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
    }

    private static String label(File directory, File file) {
        return directory.getName() + '/' + file.getName();
    }

    /** Immutable result of one scan. */
    public record LoadResult(Map<String, ProductDefinition> mobs,
                             Map<String, ProductDefinition> items,
                             int malformed,
                             List<String> unavailable,
                             List<String> disabled) {
        public LoadResult {
            mobs = Collections.unmodifiableMap(new LinkedHashMap<>(mobs));
            items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
            unavailable = List.copyOf(unavailable);
            disabled = List.copyOf(disabled);
        }

        public LoadResult(Map<String, ProductDefinition> mobs, Map<String, ProductDefinition> items, int malformed) {
            this(mobs, items, malformed, List.of(), List.of());
        }
    }

    /** Internal signal: the product is valid but switched off or not available on this server. */
    private static final class SkippedProductException extends RuntimeException {
        final boolean disabled;
        SkippedProductException(boolean disabled) {
            super(null, null, false, false);
            this.disabled = disabled;
        }
    }

    /** Internal signal: the file is syntactically readable but semantically invalid. */
    private static final class MalformedProductException extends RuntimeException {
        MalformedProductException(String message) {
            super(message);
        }
    }
}
