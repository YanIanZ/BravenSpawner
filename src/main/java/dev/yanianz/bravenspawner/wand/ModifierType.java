package dev.yanianz.bravenspawner.wand;

import dev.yanianz.bravenspawner.BravenSpawner;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One modifier kind from {@code modifiers.yml}: its look plus the stat it changes.
 *
 * <p>Types are immutable and keyed by {@link Effect}, not by the config key, so a
 * stored wand keeps working when an admin renames {@code price_multiplier} to
 * {@code multiplier}: the persistent data container writes the effect's stable
 * storage key, never the editable config id.
 *
 * <p>The loader fails closed per entry. An unknown effect, a duplicate effect, or
 * a malformed minimum/maximum is reported once and the offending entry is
 * skipped (or its default used), so one bad block cannot make every wand
 * unreadable.
 */
public final class ModifierType {

    /** The stat a modifier feeds into. Storage keys are part of the item format. */
    public enum Effect {
        RANGE("range"),
        PRICE_MULTIPLIER("multiplier");

        private final String storageKey;

        Effect(String storageKey) {
            this.storageKey = storageKey;
        }

        /** Stable key written into the wand's persistent data container. */
        public String storageKey() {
            return storageKey;
        }

        /** Resolves a stored key, or null when the value belongs to a newer version. */
        public static Effect fromStorageKey(String key) {
            if (key == null) return null;
            for (Effect effect : values()) {
                if (effect.storageKey.equalsIgnoreCase(key.trim())
                        || effect.name().equalsIgnoreCase(key.trim())) {
                    return effect;
                }
            }
            return null;
        }
    }

    public static final String FILE_NAME = "modifiers.yml";

    public static final Material DEFAULT_MATERIAL = Material.PAPER;
    public static final double DEFAULT_RANGE = 0.0;
    public static final double DEFAULT_MULTIPLIER = 1.0;
    public static final double DEFAULT_RANGE_MAX = 16.0;
    public static final double DEFAULT_MULTIPLIER_MAX = 10.0;

    private final String id;
    private final Effect effect;
    private final String nameTemplate;
    private final String descriptionTemplate;
    private final Material material;
    private final boolean enchanted;
    private final double defaultValue;
    private final double maxValue;
    private final List<String> loreTemplate;
    private final String appliedLoreTemplate;

    private ModifierType(String id, Effect effect, String nameTemplate, String descriptionTemplate,
                         Material material, boolean enchanted, double defaultValue, double maxValue,
                         List<String> loreTemplate, String appliedLoreTemplate) {
        this.id = id;
        this.effect = effect;
        this.nameTemplate = nameTemplate;
        this.descriptionTemplate = descriptionTemplate;
        this.material = material;
        this.enchanted = enchanted;
        this.defaultValue = defaultValue;
        this.maxValue = maxValue;
        this.loreTemplate = List.copyOf(loreTemplate);
        this.appliedLoreTemplate = appliedLoreTemplate;
    }

    /**
     * Loads every valid modifier, keyed by effect so there is at most one type per
     * stat. Order follows the enum, which is also the order used when a wand is
     * written, keeping stored strings stable across reloads.
     */
    public static Map<Effect, ModifierType> loadAll(BravenSpawner plugin) {
        YamlConfiguration root = WandFiles.load(plugin, FILE_NAME);
        Set<String> warned = new HashSet<>();
        Map<Effect, ModifierType> loaded = new EnumMap<>(Effect.class);

        ConfigurationSection section = root.getConfigurationSection("modifiers");
        if (section == null) {
            WandFiles.warnOnce(plugin, warned, "modifiers",
                    FILE_NAME + " has no modifiers section; sell wands carry no modifiers.");
            return Collections.unmodifiableMap(loaded);
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                WandFiles.warnOnce(plugin, warned, "modifiers." + key,
                        FILE_NAME + " modifiers." + key + " is not a section; skipped.");
                continue;
            }

            String id = key.toLowerCase(Locale.ROOT);
            Effect effect = effect(plugin, warned, id, entry.getString("affects"));
            if (effect == null) continue;
            if (loaded.containsKey(effect)) {
                WandFiles.warnOnce(plugin, warned, "modifiers." + key + ".duplicate",
                        FILE_NAME + " modifiers." + key + " repeats effect " + effect.name()
                                + "; the earlier entry is kept.");
                continue;
            }

            double defaultValue = WandFiles.number(plugin, warned, entry, "default_value", defaultOf(effect));
            if (defaultValue < 0.0) {
                WandFiles.warnOnce(plugin, warned, "modifiers." + key + ".default_value",
                        FILE_NAME + " modifiers." + key + ".default_value is negative; "
                                + defaultOf(effect) + " is used.");
                defaultValue = defaultOf(effect);
            }

            double maxValue = WandFiles.number(plugin, warned, entry, "max_value", maxOf(effect));
            if (maxValue < 0.0) {
                WandFiles.warnOnce(plugin, warned, "modifiers." + key + ".max_value",
                        FILE_NAME + " modifiers." + key + ".max_value is negative; "
                                + maxOf(effect) + " is used.");
                maxValue = maxOf(effect);
            }
            maxValue = Math.max(maxValue, defaultValue);

            Material material = material(plugin, warned, id, entry.getString("material"));
            List<String> lore;
            if (!entry.isSet("lore")) {
                lore = List.of();
            } else if (!entry.isList("lore")) {
                WandFiles.warnOnce(plugin, warned, "modifiers." + key + ".lore",
                        FILE_NAME + " modifiers." + key + ".lore is not a list; the item lore is empty.");
                lore = List.of();
            } else {
                lore = new ArrayList<>(entry.getStringList("lore"));
            }
            String name = text(entry, "name", "&e" + key);
            String description = text(entry, "description", "");
            String appliedLore = text(entry, "applied_lore", "&7" + key + ": %value%");

            loaded.put(effect, new ModifierType(id, effect, name, description, material,
                    WandFiles.flag(plugin, warned, entry, "enchanted", false),
                    defaultValue, maxValue, lore, appliedLore));
        }

        return Collections.unmodifiableMap(loaded);
    }

    private static Effect effect(BravenSpawner plugin, Set<String> warned, String id, String affects) {
        if (affects != null && !affects.isBlank()) {
            String normalized = affects.trim().toUpperCase(Locale.ROOT).replace('_', '-');
            return switch (normalized) {
                case "RANGE", "RADIUS" -> Effect.RANGE;
                case "PRICE-MULTIPLIER", "MULTIPLIER", "PRICE" -> Effect.PRICE_MULTIPLIER;
                default -> {
                    WandFiles.warnOnce(plugin, warned, "modifiers." + id + ".affects",
                            FILE_NAME + " modifiers." + id + ".affects '" + affects
                                    + "' is not a known effect; skipped.");
                    yield null;
                }
            };
        }

        // The reference file has no affects field, so derive it from the key; an
        // unrecognised key must not silently become an inert modifier.
        if (id.contains("range") || id.contains("radius")) return Effect.RANGE;
        if (id.contains("price") || id.contains("multiplier")) return Effect.PRICE_MULTIPLIER;
        WandFiles.warnOnce(plugin, warned, "modifiers." + id + ".affects",
                FILE_NAME + " modifiers." + id + " has no affects and its key names no known effect; skipped.");
        return null;
    }

    private static Material material(BravenSpawner plugin, Set<String> warned, String id, String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_MATERIAL;
        Material material = Material.matchMaterial(raw.trim());
        if (material == null || material.isAir()) {
            WandFiles.warnOnce(plugin, warned, "modifiers." + id + ".material",
                    FILE_NAME + " modifiers." + id + ".material '" + raw + "' is not a material; using "
                            + DEFAULT_MATERIAL.name() + ".");
            return DEFAULT_MATERIAL;
        }
        return material;
    }

    private static String text(ConfigurationSection section, String path, String fallback) {
        String value = section.getString(path);
        return value == null ? fallback : value;
    }

    private static double defaultOf(Effect effect) {
        return effect == Effect.RANGE ? DEFAULT_RANGE : DEFAULT_MULTIPLIER;
    }

    private static double maxOf(Effect effect) {
        return effect == Effect.RANGE ? DEFAULT_RANGE_MAX : DEFAULT_MULTIPLIER_MAX;
    }

    public String id() { return id; }
    public Effect effect() { return effect; }
    public String nameTemplate() { return nameTemplate; }
    public String descriptionTemplate() { return descriptionTemplate; }
    public Material material() { return material; }
    public boolean enchanted() { return enchanted; }
    public double defaultValue() { return defaultValue; }
    public double maxValue() { return maxValue; }
    public List<String> loreTemplate() { return loreTemplate; }
    public String appliedLoreTemplate() { return appliedLoreTemplate; }
}
