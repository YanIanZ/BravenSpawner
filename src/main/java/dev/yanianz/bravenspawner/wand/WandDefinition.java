package dev.yanianz.bravenspawner.wand;

import dev.yanianz.bravenspawner.BravenSpawner;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of {@code sellwand.yml}.
 *
 * <p>The file is parsed once at install or reload, never during an interaction:
 * a region thread must not read YAML while it is selling, and an admin edit is
 * only meant to take effect after an explicit reload. Reading it into plain
 * values also means a mid-sale config swap cannot change the radius or the
 * cooldown of the click that is already running.
 *
 * <p>Fails closed: every malformed scalar is replaced by the documented default
 * in this class and reported once per load. A radius typo therefore falls back to
 * five blocks instead of becoming an unbounded area scan, and a bad material
 * falls back to a blaze rod instead of producing an item with no meta.
 */
public final class WandDefinition {

    /** Bundled resource name; also the file an admin edits. */
    public static final String FILE_NAME = "sellwand.yml";

    /** Documented default when {@code settings.material} is missing or invalid. */
    public static final Material DEFAULT_MATERIAL = Material.BLAZE_ROD;

    /** Documented default when {@code collect.radius} is missing or invalid. */
    public static final double DEFAULT_RADIUS = 5.0;

    /** Upper bound for the area scan; a larger configured value is clamped. */
    public static final double MAX_RADIUS = 64.0;

    /** Documented default when {@code settings.cooldown-millis} is missing or invalid. */
    public static final int DEFAULT_COOLDOWN_MILLIS = 1_000;

    /** Upper bound for the per-player cooldown; larger values are clamped. */
    public static final int MAX_COOLDOWN_MILLIS = 60_000;

    /** Documented default when {@code settings.modifier-slots} is missing or invalid. */
    public static final int DEFAULT_MODIFIER_SLOTS = 2;

    /** Upper bound for modifier slots; larger values are clamped. */
    public static final int MAX_MODIFIER_SLOTS = 32;

    public static final String DEFAULT_NAME = "&6&lSell Wand";
    public static final List<String> DEFAULT_LORE = List.of(
            "&7Right-click a spawner to sell it.",
            "&7Right-click anywhere else to sell nearby spawners.");
    public static final String DEFAULT_MODIFIER_HEADER = "&8✦ &7Modifiers:";
    public static final String DEFAULT_MODIFIER_SEPARATOR = "&8⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯";
    public static final String DEFAULT_MODIFIER_FOOTER = "NONE";
    public static final String DEFAULT_SOLD_MESSAGE =
            "&aSold &f%items% &aitem(s) from &f%spawners% &aspawner(s) for &f%value%&a.";
    public static final String DEFAULT_NOTHING_MESSAGE = "&7No sellable spawners were found nearby.";
    public static final String DEFAULT_COOLDOWN_MESSAGE = "&cThe sell wand is still cooling down.";
    public static final String DEFAULT_MODIFIER_APPLIED_MESSAGE = "&aModifier applied successfully.";
    public static final String DEFAULT_MODIFIER_FAILED_MESSAGE =
            "&cFailed to apply that modifier. The wand may already have this type or be full.";

    private final YamlConfiguration layout;
    private final boolean enabled;
    private final Material material;
    private final boolean glow;
    private final boolean allowOverride;
    private final int modifierSlots;
    private final int cooldownMillis;
    private final boolean withinRadius;
    private final double radius;
    private final String nameTemplate;
    private final List<String> loreTemplate;
    private final String modifierHeader;
    private final String modifierSeparator;
    private final String modifierFooter;
    private final String messageSold;
    private final String messageNothing;
    private final String messageCooldown;
    private final String messageModifierApplied;
    private final String messageModifierFailed;

    private WandDefinition(boolean enabled, Material material, boolean glow, boolean allowOverride,
                           int modifierSlots, int cooldownMillis, boolean withinRadius, double radius,
                           String nameTemplate, List<String> loreTemplate, String modifierHeader,
                           String modifierSeparator, String modifierFooter, String messageSold,
                           String messageNothing, String messageCooldown, String messageModifierApplied,
                           String messageModifierFailed, YamlConfiguration layout) {
        this.layout = layout;
        this.enabled = enabled;
        this.material = material;
        this.glow = glow;
        this.allowOverride = allowOverride;
        this.modifierSlots = modifierSlots;
        this.cooldownMillis = cooldownMillis;
        this.withinRadius = withinRadius;
        this.radius = radius;
        this.nameTemplate = nameTemplate;
        this.loreTemplate = List.copyOf(loreTemplate);
        this.modifierHeader = modifierHeader;
        this.modifierSeparator = modifierSeparator;
        this.modifierFooter = modifierFooter;
        this.messageSold = messageSold;
        this.messageNothing = messageNothing;
        this.messageCooldown = messageCooldown;
        this.messageModifierApplied = messageModifierApplied;
        this.messageModifierFailed = messageModifierFailed;
    }

    /** Reads the data-folder copy, creating it from the bundled resource on first boot. */
    public static WandDefinition load(BravenSpawner plugin) {
        YamlConfiguration root = WandFiles.load(plugin, FILE_NAME);
        Set<String> warned = new HashSet<>();

        ConfigurationSection settings = section(plugin, warned, root, "settings");
        ConfigurationSection collect = section(plugin, warned, root, "collect");
        ConfigurationSection modifierSection = section(plugin, warned, root, "modifier-section");
        ConfigurationSection messages = section(plugin, warned, root, "messages");

        boolean enabled = WandFiles.flag(plugin, warned, settings, "enabled", true);
        Material material = material(plugin, warned, settings);
        boolean glow = WandFiles.flag(plugin, warned, settings, "glow", true);
        boolean allowOverride = WandFiles.flag(plugin, warned, settings, "allow-override", true);

        int modifierSlots = WandFiles.integer(plugin, warned, settings,
                "modifier-slots", DEFAULT_MODIFIER_SLOTS);
        if (modifierSlots < 0) {
            WandFiles.warnOnce(plugin, warned, "settings.modifier-slots",
                    "sellwand.yml settings.modifier-slots is negative; 0 (unlimited) is used.");
            modifierSlots = 0;
        } else if (modifierSlots > MAX_MODIFIER_SLOTS) {
            WandFiles.warnOnce(plugin, warned, "settings.modifier-slots",
                    "sellwand.yml settings.modifier-slots above " + MAX_MODIFIER_SLOTS
                            + " is clamped to " + MAX_MODIFIER_SLOTS + ".");
            modifierSlots = MAX_MODIFIER_SLOTS;
        }

        int cooldownMillis = WandFiles.integer(plugin, warned, settings,
                "cooldown-millis", DEFAULT_COOLDOWN_MILLIS);
        if (cooldownMillis < 0) {
            WandFiles.warnOnce(plugin, warned, "settings.cooldown-millis",
                    "sellwand.yml settings.cooldown-millis is negative; 0 (no cooldown) is used.");
            cooldownMillis = 0;
        } else if (cooldownMillis > MAX_COOLDOWN_MILLIS) {
            WandFiles.warnOnce(plugin, warned, "settings.cooldown-millis",
                    "sellwand.yml settings.cooldown-millis above " + MAX_COOLDOWN_MILLIS
                            + " is clamped to " + MAX_COOLDOWN_MILLIS + ".");
            cooldownMillis = MAX_COOLDOWN_MILLIS;
        }

        boolean withinRadius = WandFiles.flag(plugin, warned, collect, "within-radius", true);
        double radius = WandFiles.number(plugin, warned, collect, "radius", DEFAULT_RADIUS);
        if (radius < 0.0) {
            WandFiles.warnOnce(plugin, warned, "collect.radius",
                    "sellwand.yml collect.radius is negative; " + DEFAULT_RADIUS + " is used.");
            radius = DEFAULT_RADIUS;
        } else if (radius > MAX_RADIUS) {
            WandFiles.warnOnce(plugin, warned, "collect.radius",
                    "sellwand.yml collect.radius above " + MAX_RADIUS
                            + " is clamped to " + MAX_RADIUS + ".");
            radius = MAX_RADIUS;
        }

        String name = firstText(settings.getString("name"),
                root.getString("wand-types.name"),
                root.getString("wand-types.infinite.name"),
                DEFAULT_NAME);
        List<String> lore = lore(plugin, warned, settings, "lore",
                lore(plugin, warned, root, "wand-types.lore",
                        lore(plugin, warned, root, "wand-types.infinite.lore", DEFAULT_LORE)));

        String header = optionalLine(modifierSection.getString("header", DEFAULT_MODIFIER_HEADER));
        String separator = optionalLine(modifierSection.getString("separator", DEFAULT_MODIFIER_SEPARATOR));
        String footer = optionalLine(modifierSection.getString("footer", DEFAULT_MODIFIER_FOOTER));

        return new WandDefinition(
                enabled, material, glow, allowOverride, modifierSlots, cooldownMillis,
                withinRadius, radius, name, lore, header, separator, footer,
                messages.getString("sold", DEFAULT_SOLD_MESSAGE),
                messages.getString("nothing", DEFAULT_NOTHING_MESSAGE),
                messages.getString("cooldown", DEFAULT_COOLDOWN_MESSAGE),
                messages.getString("modifier-applied", DEFAULT_MODIFIER_APPLIED_MESSAGE),
                messages.getString("modifier-failed", DEFAULT_MODIFIER_FAILED_MESSAGE), root);
    }

    private static ConfigurationSection section(BravenSpawner plugin, Set<String> warned,
                                                ConfigurationSection root, String path) {
        ConfigurationSection section = root.getConfigurationSection(path);
        if (section != null) return section;
        WandFiles.warnOnce(plugin, warned, path,
                FILE_NAME + " has no " + path + " section; documented defaults are in effect.");
        return new YamlConfiguration();
    }

    private static Material material(BravenSpawner plugin, Set<String> warned, ConfigurationSection settings) {
        Object raw = settings.get("material");
        if (raw == null) return DEFAULT_MATERIAL;
        String text = String.valueOf(raw).trim();
        Material material = Material.matchMaterial(text);
        if (material == null || material.isAir()) {
            WandFiles.warnOnce(plugin, warned, "settings.material",
                    "sellwand.yml settings.material '" + text + "' is not a block/item material; using "
                            + DEFAULT_MATERIAL.name() + ".");
            return DEFAULT_MATERIAL;
        }
        return material;
    }

    /**
     * Picks the first non-blank template.
     *
     * <p>The fallback chain lets a stock VirtualSpawner {@code sellwand.yml} work
     * unchanged: that file puts the wand name under {@code wand-types.infinite}
     * rather than at the top of {@code settings}.
     */
    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return DEFAULT_NAME;
    }

    /**
     * Reads a lore block, preserving an intentionally empty list.
     *
     * <p>A scalar where a list is expected is malformed and falls back; an empty
     * list is a valid way to ship a wand with no base lore.
     */
    private static List<String> lore(BravenSpawner plugin, Set<String> warned,
                                     ConfigurationSection section, String path, List<String> fallback) {
        if (!section.isSet(path)) return fallback;
        if (!section.isList(path)) {
            WandFiles.warnOnce(plugin, warned, path,
                    FILE_NAME + " " + path + " is not a list; documented defaults are in effect.");
            return fallback;
        }
        return new ArrayList<>(section.getStringList(path));
    }

    /** {@code NONE} disables a decorative line; any other text is kept verbatim. */
    private static String optionalLine(String value) {
        if (value == null) return null;
        return value.equalsIgnoreCase("NONE") ? null : value;
    }

    /**
     * The name the wand should display for its lifetime mode.
     *
     * <p>Falls back to the base name when the mode has no block, which keeps a
     * config written before lifetimes existed working unchanged.
     */
    public String nameFor(WandLifetime lifetime) {
        return render(layout.getString("wand-types." + lifetime.mode() + ".name", nameTemplate), lifetime);
    }

    /**
     * Base lore plus the lifetime mode's extra lines.
     *
     * <p>Uses and day wands need to show the remaining budget; an infinite wand
     * usually has no extra line, and a missing mode block simply contributes none.
     */
    public List<String> loreFor(WandLifetime lifetime) {
        List<String> lines = new ArrayList<>(loreTemplate);
        lines.addAll(layout.getStringList("wand-types." + lifetime.mode() + ".lore"));
        return lines.stream().map(line -> render(line, lifetime)).toList();
    }

    private String render(String template, WandLifetime lifetime) {
        long remaining = Math.max(0, lifetime.expiresAt() - System.currentTimeMillis());
        String date = "";
        if (lifetime.expiresAt() > 0) {
            java.time.format.DateTimeFormatter formatter;
            try { formatter = java.time.format.DateTimeFormatter.ofPattern(layout.getString("date-format", "yyyy-MM-dd HH:mm:ss")); }
            catch (IllegalArgumentException exception) { formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); }
            date = formatter.withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(lifetime.expiresAt()));
        }
        return template.replace("%uses%", Integer.toString(lifetime.uses()))
                .replace("%days%", Long.toString(remaining / WandLifetime.DAY_MILLIS + (remaining % WandLifetime.DAY_MILLIS > 0 ? 1 : 0)))
                .replace("%expiration_date%", date);
    }

    /** A message from the {@code messages} block, with a documented fallback. */
    public String lifetimeMessage(String key, String fallback) {
        return layout.getString("messages." + key, fallback);
    }

    public boolean enabled() { return enabled; }
    public Material material() { return material; }
    public boolean glow() { return glow; }
    public boolean allowOverride() { return allowOverride; }
    public int modifierSlots() { return modifierSlots; }
    public int cooldownMillis() { return cooldownMillis; }
    public boolean withinRadius() { return withinRadius; }
    public double radius() { return radius; }
    public String nameTemplate() { return nameTemplate; }
    public List<String> loreTemplate() { return loreTemplate; }
    public String modifierHeader() { return modifierHeader; }
    public String modifierSeparator() { return modifierSeparator; }
    public String modifierFooter() { return modifierFooter; }
    public String messageSold() { return messageSold; }
    public String messageNothing() { return messageNothing; }
    public String messageCooldown() { return messageCooldown; }
    public String messageModifierApplied() { return messageModifierApplied; }
    public String messageModifierFailed() { return messageModifierFailed; }
}
