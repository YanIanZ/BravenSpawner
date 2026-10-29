package dev.yanianz.bravenspawner.menu;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One parsed {@code gui/*.yml} file: title, size, named layouts and the items
 * those layouts place.
 *
 * <p>The model is immutable and server-agnostic; building an inventory is
 * {@link MenuRenderer}'s job. Parsing is deliberately strict — a row that is not
 * nine characters, a duplicate layout symbol or an unknown material is a
 * configuration error, and skipping the menu is safer than rendering a
 * half-empty inventory nobody can explain.
 */
public final class MenuDefinition {

    /** Layout name used when a menu has a single unnamed layout. */
    public static final String DEFAULT_LAYOUT = "default";

    private static final int COLUMNS = 9;
    private static final int MIN_ROWS = 1;
    private static final int MAX_ROWS = 6;

    private final String name;
    private final String title;
    private final int rows;
    private final Map<String, List<String>> layouts;
    private final Map<Character, ItemDefinition> itemsBySymbol;
    private final Map<String, ItemDefinition> itemsById;
    private final char filler;
    private final List<Integer> puzzleSlots;
    private final Map<String, String> sounds;
    private final Map<String, String> messages;

    private MenuDefinition(String name, String title, int rows,
                           Map<String, List<String>> layouts,
                           Map<Character, ItemDefinition> itemsBySymbol,
                           Map<String, ItemDefinition> itemsById,
                           char filler, List<Integer> puzzleSlots, Map<String, String> sounds, Map<String, String> messages) {
        this.name = name;
        this.title = title;
        this.rows = rows;
        this.layouts = Collections.unmodifiableMap(layouts);
        this.itemsBySymbol = Collections.unmodifiableMap(itemsBySymbol);
        this.itemsById = Collections.unmodifiableMap(itemsById);
        this.filler = filler;
        this.puzzleSlots = List.copyOf(puzzleSlots);
        this.sounds = Map.copyOf(sounds);
        this.messages = Map.copyOf(messages);
    }

    /**
     * Parses one menu.
     *
     * @param name  logical menu name (the file name without {@code .yml})
     * @param menu  the {@code menu:} section
     * @param items the {@code items:} section
     * @throws IllegalArgumentException when the file cannot be rendered faithfully
     */
    public static MenuDefinition parse(String name, ConfigurationSection menu, ConfigurationSection items) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(items, "items");

        Map<String, List<String>> layouts = readLayouts(menu);
        int rows = readRowCount(menu, layouts);
        String title = menu.getString("title", name);

        Map<Character, ItemDefinition> bySymbol = new LinkedHashMap<>();
        Map<String, ItemDefinition> byId = new LinkedHashMap<>();
        char filler = '-';
        boolean fillerSeen = false;

        for (String id : items.getKeys(false)) {
            ConfigurationSection section = items.getConfigurationSection(id);
            if (section == null) {
                throw new IllegalArgumentException("items." + id + " is not a section");
            }
            char symbol = readSymbol(id, section);
            ItemDefinition item = readItem(id, symbol, section);
            ItemDefinition previous = bySymbol.putIfAbsent(symbol, item);
            if (previous != null) {
                throw new IllegalArgumentException("symbol '" + symbol + "' is used by both "
                        + previous.id() + " and " + id);
            }
            byId.put(id, item);
            if (id.equalsIgnoreCase("filler")) {
                filler = symbol;
                fillerSeen = true;
            }
        }
        if (!fillerSeen) {
            throw new IllegalArgumentException("items.filler is required");
        }
        List<Integer> puzzleSlots = menu.getIntegerList("puzzle_slots");
        if (puzzleSlots.stream().anyMatch(slot -> slot < 0 || slot >= rows * COLUMNS)
                || new java.util.HashSet<>(puzzleSlots).size() != puzzleSlots.size()) {
            throw new IllegalArgumentException("puzzle_slots must contain unique inventory slots inside the menu");
        }
        ConfigurationSection root = menu.getParent();
        return new MenuDefinition(name, title == null ? name : title, rows, layouts, bySymbol, byId, filler,
                puzzleSlots, strings(root == null ? null : root.getConfigurationSection("sounds")),
                strings(root == null ? null : root.getConfigurationSection("messages")));
    }

    private static Map<String, String> strings(ConfigurationSection section) {
        Map<String, String> result = new LinkedHashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) result.put(key, section.getString(key, ""));
        }
        return result;
    }

    public List<Integer> puzzleSlots() { return puzzleSlots; }
    public String sound(String event) { return sounds.getOrDefault(event, ""); }
    public String message(String event) { return messages.getOrDefault(event, ""); }

    /**
     * Reads either a named layout section ({@code layout: {default: [...]}}) or a
     * single unnamed layout ({@code layout: [...]} or {@code layout: "row"}).
     *
     * <p>Both shapes exist in the shipped menus, so accepting only one of them
     * would silently break half of the original files.
     */
    private static Map<String, List<String>> readLayouts(ConfigurationSection menu) {
        Map<String, List<String>> layouts = new LinkedHashMap<>();
        ConfigurationSection named = menu.getConfigurationSection("layout");
        if (named != null) {
            for (String key : named.getKeys(false)) {
                layouts.put(key, readLayoutRows(key, named.get(key)));
            }
            return layouts;
        }
        if (menu.contains("layout")) {
            layouts.put(DEFAULT_LAYOUT, readLayoutRows(DEFAULT_LAYOUT, menu.get("layout")));
        }
        return layouts;
    }

    private static List<String> readLayoutRows(String layout, Object raw) {
        List<String> rows = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object row : list) rows.add(validateRow(layout, row));
        } else if (raw != null) {
            rows.add(validateRow(layout, raw));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("layout '" + layout + "' has no rows");
        }
        return List.copyOf(rows);
    }

    private static String validateRow(String layout, Object raw) {
        String row = raw == null ? "" : String.valueOf(raw);
        if (row.length() != COLUMNS) {
            throw new IllegalArgumentException("layout '" + layout + "' row '" + row
                    + "' must be exactly " + COLUMNS + " characters");
        }
        return row;
    }

    /**
     * Explicit {@code rows} wins; otherwise {@code size} is accepted for menus
     * that describe a chest instead of a layout; otherwise the tallest layout
     * decides. The cap is six because a chest cannot be taller.
     */
    private static int readRowCount(ConfigurationSection menu, Map<String, List<String>> layouts) {
        int rows;
        if (menu.contains("rows")) {
            rows = menu.getInt("rows");
        } else if (menu.contains("size")) {
            int size = menu.getInt("size");
            if (size % COLUMNS != 0) {
                throw new IllegalArgumentException("size " + size + " is not a multiple of " + COLUMNS);
            }
            rows = size / COLUMNS;
        } else {
            rows = 0;
            for (List<String> layout : layouts.values()) {
                rows = Math.max(rows, layout.size());
            }
            rows = Math.max(rows, MIN_ROWS);
        }
        if (rows < MIN_ROWS || rows > MAX_ROWS) {
            throw new IllegalArgumentException("rows " + rows + " is outside " + MIN_ROWS + ".." + MAX_ROWS);
        }
        return rows;
    }

    /**
     * A symbol is required for layout placement, but the corruption puzzle has no
     * layout. Falling back to the item id's first character keeps every item
     * addressable by both id and character without forcing a synthetic layout.
     */
    private static char readSymbol(String id, ConfigurationSection section) {
        String configured = section.getString("symbol");
        if (configured != null && !configured.isEmpty()) {
            return configured.charAt(0);
        }
        if (id.isEmpty()) {
            throw new IllegalArgumentException("item id is empty");
        }
        return id.charAt(0);
    }

    private static ItemDefinition readItem(String id, char symbol, ConfigurationSection section) {
        String materialName = section.getString("material", "STONE");
        Material material = Material.matchMaterial(materialName == null ? "" : materialName);
        if (material == null) {
            throw new IllegalArgumentException("items." + id + " has unknown material '" + materialName + "'");
        }
        String display = section.getString("name", "");
        List<String> lore = List.copyOf(section.getStringList("lore"));
        int customModel = section.getInt("custom-model", -1);
        String texture = section.getString("head-texture", "");
        String action = section.getString("nbt", "");
        boolean glowing = section.getBoolean("glowing", false);
        return new ItemDefinition(id, symbol, material,
                display == null ? "" : display,
                lore,
                customModel,
                texture == null ? "" : texture,
                action == null ? "" : action,
                glowing);
    }

    /** Logical name, matching the file name without its extension. */
    public String name() {
        return name;
    }

    /** Raw title; placeholders are still present and are resolved by the renderer. */
    public String title() {
        return title;
    }

    /** Chest rows, always inside 1..6. */
    public int rows() {
        return rows;
    }

    /** Named layouts, each with validated nine-character rows. */
    public Map<String, List<String>> layouts() {
        return layouts;
    }

    public boolean hasLayout(String layout) {
        return layout != null && layouts.containsKey(layout);
    }

    /** The layout an open call uses when the caller does not name one. */
    public String defaultLayout() {
        if (layouts.containsKey(DEFAULT_LAYOUT)) return DEFAULT_LAYOUT;
        return layouts.isEmpty() ? DEFAULT_LAYOUT : layouts.keySet().iterator().next();
    }

    /**
     * Rows for the requested layout.
     *
     * <p>An unknown layout falls back to {@code default} and then to the first
     * layout. A menu with no layout at all (the corruption puzzle) returns an
     * empty list and renders as pure filler.
     */
    public List<String> layout(String layout) {
        List<String> rows = layouts.get(layout == null ? DEFAULT_LAYOUT : layout);
        if (rows != null) return rows;
        rows = layouts.get(DEFAULT_LAYOUT);
        if (rows != null) return rows;
        return layouts.isEmpty() ? List.of() : layouts.values().iterator().next();
    }

    /** Symbol placed at an absolute slot, or {@code 0} when the slot is outside the layout. */
    public char symbol(String layout, int slot) {
        if (slot < 0) return 0;
        List<String> rows = layout(layout);
        int row = slot / COLUMNS;
        int column = slot % COLUMNS;
        if (row < 0 || row >= rows.size()) return 0;
        String line = rows.get(row);
        return column < line.length() ? line.charAt(column) : 0;
    }

    /** Item bound to a layout symbol, or null when the symbol is unused. */
    public ItemDefinition item(char symbol) {
        return itemsBySymbol.get(symbol);
    }

    /** Item bound to its YAML id, used by menus that have no layout (the puzzle). */
    public ItemDefinition item(String id) {
        return id == null ? null : itemsById.get(id);
    }

    /** Every item keyed by its layout symbol. */
    public Map<Character, ItemDefinition> items() {
        return itemsBySymbol;
    }

    /** Symbol reserved for empty slots. */
    public char filler() {
        return filler;
    }

    /** One item template: everything needed to build a fresh stack per render. */
    public static final class ItemDefinition {

        private final String id;
        private final char symbol;
        private final Material material;
        private final String name;
        private final List<String> lore;
        private final int customModel;
        private final String headTexture;
        private final String action;
        private final boolean glowing;

        private ItemDefinition(String id, char symbol, Material material, String name, List<String> lore,
                               int customModel, String headTexture, String action, boolean glowing) {
            this.id = id;
            this.symbol = symbol;
            this.material = material;
            this.name = name;
            this.lore = lore;
            this.customModel = customModel;
            this.headTexture = headTexture;
            this.action = action;
            this.glowing = glowing;
        }

        public String id() {
            return id;
        }

        public char symbol() {
            return symbol;
        }

        public Material material() {
            return material;
        }

        /** Raw display name; may contain placeholders. */
        public String name() {
            return name;
        }

        /** Raw lore lines; may contain placeholders. */
        public List<String> lore() {
            return lore;
        }

        /** Custom model data, or {@code -1} when the item must not carry any. */
        public int customModel() {
            return customModel;
        }

        /** Texture URL, texture hash, base64 payload or placeholder; empty when unused. */
        public String headTexture() {
            return headTexture;
        }

        /**
         * Optional action identifier carried over from the original menus.
         *
         * <p>Routing is by layout symbol, but this value stays parsed so a menu
         * that has no layout can name its buttons without inventing symbols.
         */
        public String action() {
            return action;
        }

        public boolean glowing() {
            return glowing;
        }
    }
}
