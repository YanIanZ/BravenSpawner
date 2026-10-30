package dev.yanianz.bravenspawner.menu;

import dev.yanianz.bravenspawner.util.HeadTextures;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Turns a {@link MenuDefinition} plus a placeholder map into a live inventory.
 *
 * <p>Rendering is a single forward pass: pre-fill everything with the filler,
 * then overwrite the slots the layout names. Items are rebuilt from their
 * templates on every pass, because an {@link ItemStack} stored in a definition
 * would be mutable and two viewers would silently share one stack's meta.
 *
 * <p>Placeholders are substituted once, in the definition's own text and in a
 * head texture value. Nothing is expanded recursively: a value that happens to
 * contain {@code %...%} text is data, not a second placeholder, which also keeps
 * user-supplied names from expanding something they should not.
 */
public final class MenuRenderer {

    private static final int COLUMNS = 9;
    private static final int MAX_ROWS = 6;
    /** Placeholder holding the product's head texture; {@code spawnerhead} resolves to it. */
    public static final String HEAD_TEXTURE = "head_texture";
    /** Placeholder holding the material of the product's head icon. */
    public static final String HEAD_MATERIAL = "head_material";

    /**
     * Builds an inventory with a throwaway holder.
     *
     * <p>Useful for previews and tests; interactive menus must go through
     * {@link MenuService#open} so the holder is registered and clicks route.
     */
    public Inventory render(MenuDefinition definition, String layoutName, Map<String, String> placeholders) {
        Objects.requireNonNull(definition, "definition");
        MenuHolder holder = new MenuHolder(new MenuSession(definition.name(), layoutName, placeholders, 0L));
        return render(definition, layoutName, placeholders, holder);
    }

    /**
     * Builds the inventory and binds it to its holder.
     *
     * <p>The holder is attached here rather than by the caller so there is no
     * window in which a rendered inventory exists without a current identity.
     *
     * @param layoutName layout to place; unknown names fall back inside the definition
     * @return the created inventory, already owned by {@code holder}
     */
    public Inventory render(MenuDefinition definition, String layoutName,
                            Map<String, String> placeholders, MenuHolder holder) {
        return render(definition, layoutName, placeholders, holder, UnaryOperator.identity());
    }

    public Inventory render(MenuDefinition definition, String layoutName,
                            Map<String, String> placeholders, MenuHolder holder,
                            UnaryOperator<String> resolver) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(holder, "holder");
        Map<String, String> values = placeholders == null ? Map.of() : placeholders;

        int rows = Math.max(1, Math.min(MAX_ROWS, definition.rows()));
        Inventory inventory = Bukkit.createInventory(holder, rows * COLUMNS,
                Text.of(resolver.apply(substitute(definition.title(), values))));
        holder.attach(inventory);

        ItemStack filler = buildItem(definition.item(definition.filler()), values, resolver);
        if (filler != null) {
            int size = inventory.getSize();
            for (int slot = 0; slot < size; slot++) {
                inventory.setItem(slot, filler.clone());
            }
        }

        List<String> layout = definition.layout(layoutName);
        int layoutRows = Math.min(layout.size(), rows);
        for (int row = 0; row < layoutRows; row++) {
            String line = layout.get(row);
            int columns = Math.min(COLUMNS, line.length());
            for (int column = 0; column < columns; column++) {
                char symbol = line.charAt(column);
                if (symbol == definition.filler()) continue;
                ItemStack item = buildItem(definition.item(symbol), values, resolver);
                if (item != null) inventory.setItem(row * COLUMNS + column, item);
            }
        }
        return inventory;
    }

    public static ItemStack buildItem(MenuDefinition.ItemDefinition item, Map<String, String> placeholders) {
        return buildItem(item, placeholders, UnaryOperator.identity());
    }

    public static ItemStack buildItem(MenuDefinition.ItemDefinition item, Map<String, String> placeholders,
                                     UnaryOperator<String> resolver) {
        if (item == null) return null;
        boolean spawnerHead = HeadTextures.isSpawnerToken(item.headTexture());
        ItemStack stack = new ItemStack(spawnerHead ? spawnerHeadMaterial(item.material(), placeholders) : item.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        meta.displayName(Text.item(resolver.apply(substitute(item.name(), placeholders))));
        List<String> lore = item.lore();
        if (!lore.isEmpty()) {
            List<String> lines = new ArrayList<>(lore.size());
            for (String line : lore) lines.add(resolver.apply(substitute(line, placeholders)));
            meta.lore(Text.items(lines));
        }
        if (item.customModel() >= 0) {
            meta.setCustomModelData(item.customModel());
        }
        if (item.glowing()) {
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
        }
        if (meta instanceof SkullMeta skull) {
            String texture = spawnerHead ? "%" + HEAD_TEXTURE + "%" : item.headTexture();
            HeadTextures.apply(skull, resolver.apply(substitute(texture, placeholders)));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * A {@code spawnerhead} item shows the product's own head: its configured
     * material when one is supplied, otherwise the template material.
     */
    private static Material spawnerHeadMaterial(Material template, Map<String, String> placeholders) {
        String name = placeholders == null ? null : placeholders.get(HEAD_MATERIAL);
        Material material = name == null || name.isBlank() ? null : Material.matchMaterial(name.trim());
        return material == null || material.isAir() || !material.isItem() ? template : material;
    }

    /**
     * Replaces {@code %key%} tokens literally, never as a regex.
     *
     * <p>Accepting a key with or without its percent signs keeps YAML tidy while
     * still allowing a caller to store the token verbatim.
     */
    private static String substitute(String raw, Map<String, String> placeholders) {
        if (raw == null) return "";
        if (raw.isEmpty() || placeholders.isEmpty()) return raw;
        Map<String, String> tokens = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isEmpty()) continue;
            String token = key.length() > 1 && key.startsWith("%") && key.endsWith("%")
                    ? key : "%" + key + "%";
            tokens.put(token, entry.getValue() == null ? "" : entry.getValue());
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("%[^%]+%").matcher(raw);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = tokens.getOrDefault(matcher.group(), matcher.group());
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
