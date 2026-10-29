package dev.yanianz.bravenspawner.product;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/**
 * One product (one YAML file): everything a placed spawner needs to produce loot,
 * plus the optional stock budget and the optional item-name/lore overrides.
 *
 * <p>The type is split into {@link Kind#MOB} and {@link Kind#ITEM}: a mob product
 * identifies virtual mob loot and therefore needs an {@link EntityType}, an item product
 * generates resources directly and needs an output {@link Material}. The two
 * only ever share interval, XP, drops and stock, so keeping them in one value
 * type means a caller never has to branch on the source directory.
 *
 * <p>Instances are immutable and can be read from any thread. The class holds no
 * Bukkit handles beyond enums, so a region thread never touches a live
 * configuration object. {@link #builder(Kind, String)} is the only way in, which
 * keeps every field validated at one point instead of at each call site.
 */
public final class ProductDefinition {

    /** What the spawner produces: a living entity or direct item output. */
    public enum Kind { MOB, ITEM }

    private final Kind kind;
    private final String id;
    private final String displayName;
    private final EntityType entityType;
    private final Material outputMaterial;
    private final int intervalTicks;
    private final int xp;
    private final int maxXpPerCycle;
    private final int maxIntervalTicks;
    private final int maxStoredItems;
    private final int maxStoredXp;
    private final int maxStack;
    private final boolean mustBeFullToCollect;
    private final List<Drop> drops;
    private final boolean stockEnabled;
    private final int maxStock;
    private final int restockPerCycle;
    private final String spawnerItemName;
    private final List<String> spawnerItemLore;
    private final double spawnerPrice;

    private ProductDefinition(Builder builder) {
        this.kind = builder.kind;
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.entityType = builder.entityType;
        this.outputMaterial = builder.outputMaterial;
        this.intervalTicks = builder.intervalTicks;
        this.xp = builder.xp;
        this.maxXpPerCycle = Math.max(builder.xp, builder.maxXpPerCycle);
        this.maxIntervalTicks = Math.max(builder.intervalTicks, builder.maxIntervalTicks);
        this.maxStoredItems = builder.maxStoredItems;
        this.maxStoredXp = builder.maxStoredXp;
        this.maxStack = builder.maxStack;
        this.mustBeFullToCollect = builder.mustBeFullToCollect;
        this.drops = List.copyOf(builder.drops);
        this.stockEnabled = builder.stockEnabled;
        this.maxStock = builder.maxStock;
        this.restockPerCycle = builder.restockPerCycle;
        this.spawnerItemName = builder.spawnerItemName;
        this.spawnerItemLore = builder.spawnerItemLore == null
                ? null
                : List.copyOf(builder.spawnerItemLore);
        this.spawnerPrice = builder.spawnerPrice;
    }

    public static Builder builder(Kind kind, String id) {
        return new Builder(kind, id);
    }

    public Kind kind() { return kind; }

    public boolean isMob() { return kind == Kind.MOB; }

    /** Lower-case file name; the key placed spawners store as their product. */
    public String id() { return id; }

    public String displayName() { return displayName; }

    /** Entity type for a mob product, {@code null} for an item product. */
    public EntityType entityType() { return entityType; }

    /** Output material for an item product, {@code null} for a mob product. */
    public Material outputMaterial() { return outputMaterial; }

    /** Base production period in server ticks; tiers may override it at runtime. */
    public int intervalTicks() { return intervalTicks; }

    public int xp() { return xp; }
    public int maxXpPerCycle() { return maxXpPerCycle; }
    public int maxIntervalTicks() { return maxIntervalTicks; }
    public int maxStoredItems() { return maxStoredItems; }
    public int maxStoredXp() { return maxStoredXp; }
    /** Zero uses the global limit. */
    public int maxStack() { return maxStack; }
    public boolean mustBeFullToCollect() { return mustBeFullToCollect; }

    public List<Drop> drops() { return drops; }

    public boolean stockEnabled() { return stockEnabled; }

    public int maxStock() { return maxStock; }

    public int restockPerCycle() { return restockPerCycle; }

    /** Optional item-display override; {@code null} keeps the global config name. */
    public String spawnerItemName() { return spawnerItemName; }

    /** Optional lore override; {@code null} keeps the global config lore. */
    public List<String> spawnerItemLore() { return spawnerItemLore; }

    public double spawnerPrice() { return spawnerPrice; }

    /**
     * Turns a file stem into a readable default: {@code iron_golem} becomes
     * "Iron Golem". Only used when the file omits a display name, so a product is
     * never shown to a player as a raw file name.
     */
    private static String pretty(String id) {
        String[] words = id.split("_");
        StringBuilder out = new StringBuilder(id.length());
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.isEmpty() ? id : out.toString();
    }

    /**
     * One drop rule.
     *
     * <p>{@code chance} is stored as a probability in {@code (0, 1]} rather than a
     * percentage, so production code multiplies once and never re-parses the
     * units. The loader translates the YAML percent form at the boundary.
     */
    public record Drop(Material material, int minAmount, int maxAmount, double chance,
                       double sellPrice, String displayName, int customModelData) {
        public Drop(Material material, int minAmount, int maxAmount, double chance) {
            this(material, minAmount, maxAmount, chance, -1.0);
        }
        public Drop(Material material, int minAmount, int maxAmount, double chance, double sellPrice) {
            this(material, minAmount, maxAmount, chance, sellPrice, null, -1);
        }

        public Drop {
            Objects.requireNonNull(material, "material");
            if (minAmount < 1) throw new IllegalArgumentException("minAmount must be >= 1");
            if (maxAmount < minAmount) throw new IllegalArgumentException("maxAmount must be >= minAmount");
            if (!(chance > 0.0) || chance > 1.0) {
                throw new IllegalArgumentException("chance must be in (0,1]");
            }
        }

        /** Rolls one drop, returning {@code 0} when the chance misses. */
        public int roll(Random random) {
            Objects.requireNonNull(random, "random");
            if (random.nextDouble() >= chance) return 0;
            return minAmount == maxAmount
                    ? minAmount
                    : minAmount + random.nextInt(maxAmount - minAmount + 1);
        }
    }

    /**
     * Validating builder.
     *
     * <p>The loader parses files one field at a time and can only reject the whole
     * file at the end; a builder keeps that decision in one place and lets the
     * loader report a precise reason instead of a half-built object.
     */
    public static final class Builder {

        private final Kind kind;
        private final String id;
        private String displayName;
        private EntityType entityType;
        private Material outputMaterial;
        private int intervalTicks = 1200;
        private int xp;
        private int maxXpPerCycle;
        private int maxIntervalTicks;
        private int maxStoredItems;
        private int maxStoredXp;
        private int maxStack;
        private boolean mustBeFullToCollect;
        private final List<Drop> drops = new ArrayList<>();
        private boolean stockEnabled = true;
        private int maxStock = 1000;
        private int restockPerCycle = 8;
        private String spawnerItemName;
        private List<String> spawnerItemLore;
        private double spawnerPrice;

        private Builder(Kind kind, String id) {
            this.kind = Objects.requireNonNull(kind, "kind");
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            this.id = id.toLowerCase(Locale.ROOT);
            this.displayName = pretty(this.id);
        }

        public Builder displayName(String value) {
            if (value != null && !value.isBlank()) this.displayName = value;
            return this;
        }

        public Builder entityType(EntityType value) {
            this.entityType = value;
            return this;
        }

        public Builder outputMaterial(Material value) {
            this.outputMaterial = value;
            return this;
        }

        public Builder intervalTicks(int value) {
            this.intervalTicks = value;
            return this;
        }

        public Builder xp(int value) {
            this.xp = value;
            return this;
        }

        public Builder mustBeFullToCollect(boolean value) {
            this.mustBeFullToCollect = value;
            return this;
        }

        public Builder ranges(int maxDelay, int maxXp) {
            this.maxIntervalTicks = maxDelay;
            this.maxXpPerCycle = maxXp;
            return this;
        }

        public Builder limits(int items, int xp, int stack) {
            this.maxStoredItems = Math.max(0, items);
            this.maxStoredXp = Math.max(0, xp);
            this.maxStack = Math.max(0, stack);
            return this;
        }

        public Builder addDrop(Drop drop) {
            if (drop != null) this.drops.add(drop);
            return this;
        }

        public Builder stock(boolean enabled, int maxStock, int restockPerCycle) {
            this.stockEnabled = enabled;
            this.maxStock = maxStock;
            this.restockPerCycle = restockPerCycle;
            return this;
        }

        public Builder spawnerItemName(String value) {
            this.spawnerItemName = value == null || value.isBlank() ? null : value;
            return this;
        }

        public Builder spawnerItemLore(List<String> value) {
            this.spawnerItemLore = value == null || value.isEmpty() ? null : new ArrayList<>(value);
            return this;
        }

        public Builder spawnerPrice(double value) {
            this.spawnerPrice = value;
            return this;
        }

        public ProductDefinition build() {
            if (kind == Kind.MOB && entityType == null) {
                throw new IllegalArgumentException("mob product requires an entity type");
            }
            if (kind == Kind.ITEM && outputMaterial == null) {
                throw new IllegalArgumentException("item product requires an output material");
            }
            if (intervalTicks < 1) throw new IllegalArgumentException("intervalTicks must be >= 1");
            if (xp < 0) throw new IllegalArgumentException("xp must be >= 0");
            if (stockEnabled && maxStock < 0) throw new IllegalArgumentException("maxStock must be >= 0");
            if (stockEnabled && restockPerCycle < 0) {
                throw new IllegalArgumentException("restockPerCycle must be >= 0");
            }
            if (drops.isEmpty()) throw new IllegalArgumentException("at least one drop is required");
            return new ProductDefinition(this);
        }
    }
}
