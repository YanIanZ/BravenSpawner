package dev.yanianz.bravenspawner.wand;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A sell wand as item data: material, rendered name/lore and the two PDC fields.
 *
 * <p>The wand id is a random UUID generated when the item is first written. It is
 * stable across reads and writes, so a future per-wand ledger (uses, ownership,
 * audit trail) has a key that survives chests, shulkers and database round trips;
 * the byte comparison of the item is not used for identity because two otherwise
 * identical wands must still be distinguishable.
 *
 * <p>Modifiers are stored as one string, {@code range=5.0;multiplier=1.5}, using
 * the effect storage keys rather than the config keys. That keeps the item
 * readable when an admin renames a modifier in {@code modifiers.yml}, and it makes
 * a partially corrupted string degrade to the modifiers that still parse instead
 * of invalidating the whole wand.
 *
 * <p>Instances are immutable. {@link #read(ItemStack)} returns a new value,
 * {@link #with(Modifier)} returns a new value, and {@link #write(Wand)} produces
 * the item that carries it.
 */
public final class Wand {

    private static final String ID_PATH = "sellwand_id";
    private static final String MODIFIERS_PATH = "sellwand_modifiers";

    private final BravenSpawner plugin;
    private final WandDefinition definition;
    private final Map<ModifierType.Effect, ModifierType> types;
    private final NamespacedKey idKey;
    private final NamespacedKey modifiersKey;
    private final UUID id;
    private final WandLifetime lifetime;
    private final Map<String, Modifier> modifiers;

    private Wand(BravenSpawner plugin, WandDefinition definition,
                 Map<ModifierType.Effect, ModifierType> types, UUID id,
                 Map<String, Modifier> modifiers) {
        this(plugin, definition, types, id, modifiers, WandLifetime.INFINITE);
    }

    private Wand(BravenSpawner plugin, WandDefinition definition,
                 Map<ModifierType.Effect, ModifierType> types, UUID id,
                 Map<String, Modifier> modifiers, WandLifetime lifetime) {
        this.plugin = plugin;
        this.definition = definition;
        this.types = new java.util.EnumMap<>(ModifierType.Effect.class);
        if (types != null) this.types.putAll(types);
        this.idKey = new NamespacedKey(plugin, ID_PATH);
        this.modifiersKey = new NamespacedKey(plugin, MODIFIERS_PATH);
        this.id = id;
        this.lifetime = Objects.requireNonNull(lifetime);
        this.modifiers = canonical(modifiers);
    }

    /**
     * Creates the reader/writer used by the service for every interaction.
     *
     * <p>The template's own id is irrelevant; it only supplies the loaded
     * definition, the PDC keys and the modifier type table to static-looking
     * operations such as {@link #read(ItemStack)} and {@link #write(Wand)}.
     */
    public static Wand template(BravenSpawner plugin, WandDefinition definition,
                                Map<ModifierType.Effect, ModifierType> types) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(definition, "definition");
        return new Wand(plugin, definition, types, UUID.randomUUID(), Map.of());
    }

    /** Creates a fresh wand with a new stable id and the supplied modifiers. */
    public static Wand create(BravenSpawner plugin, WandDefinition definition,
                              Map<ModifierType.Effect, ModifierType> types,
                              Collection<Modifier> applied) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(definition, "definition");
        Map<String, Modifier> map = new LinkedHashMap<>();
        if (applied != null) {
            for (Modifier modifier : applied) {
                if (modifier != null) map.put(modifier.storageKey(), modifier);
            }
        }
        return new Wand(plugin, definition, types, UUID.randomUUID(), map);
    }

    /**
     * True when the stack is one of our wands: right material, a parseable id and
     * the same plugin's PDC namespace. A mere material match is not enough, which
     * is what keeps a normal blaze rod from selling a base.
     */
    public boolean isWand(ItemStack item) {
        return parse(item) != null;
    }

    /**
     * Reads a wand, or returns null when the stack is not one.
     *
     * <p>Malformed modifier entries are dropped individually. Returning a wand with
     * the valid subset is safer than rejecting the item: the player keeps a usable
     * wand and the next write repairs the stored string.
     */
    public Wand read(ItemStack item) {
        return parse(item);
    }

    private Wand parse(ItemStack item) {
        if (item == null || item.getType() != definition.material()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String rawId = pdc.get(idKey, PersistentDataType.STRING);
        if (rawId == null || rawId.isBlank()) return null;

        UUID parsed;
        try {
            parsed = UUID.fromString(rawId.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }

        NamespacedKey usesKey = new NamespacedKey(plugin, "sellwand_uses");
        NamespacedKey expiryKey = new NamespacedKey(plugin, "sellwand_expires");
        Integer uses = pdc.get(usesKey, PersistentDataType.INTEGER);
        Long expiry = pdc.get(expiryKey, PersistentDataType.LONG);
        // Older wands have neither field. Partial or malformed new data fails closed.
        if ((pdc.has(usesKey) || pdc.has(expiryKey)) && (uses == null || expiry == null)) return null;
        WandLifetime lifetime;
        try { lifetime = uses == null ? WandLifetime.INFINITE : new WandLifetime(uses, expiry); }
        catch (IllegalArgumentException exception) { return null; }
        return new Wand(plugin, definition, types, parsed,
                decode(pdc.get(modifiersKey, PersistentDataType.STRING)), lifetime);
    }

    /**
     * Renders a wand into an item.
     *
     * <p>The receiver supplies the layout (definition and PDC keys); the argument
     * supplies identity and contents. That split lets one loaded template write
     * every wand without every wand carrying its own copy of the parsed YAML.
     */
    public ItemStack write(Wand wand) {
        if (wand == null) return null;

        ItemStack item = new ItemStack(definition.material());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(Text.item(definition.nameFor(wand.lifetime)));

        List<String> lore = new ArrayList<>(definition.loreFor(wand.lifetime));
        List<Modifier> applied = new ArrayList<>(wand.modifiers.values());
        if (!applied.isEmpty()) {
            if (!lore.isEmpty() && !lore.get(lore.size() - 1).isBlank()) lore.add("");
            if (definition.modifierHeader() != null) lore.add(definition.modifierHeader());
            if (definition.modifierSeparator() != null) lore.add(definition.modifierSeparator());
            for (Modifier modifier : applied) lore.add(modifier.appliedLore());
            if (definition.modifierFooter() != null) lore.add(definition.modifierFooter());
        }
        meta.lore(Text.items(lore));

        if (definition.glow()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(idKey, PersistentDataType.STRING, wand.id.toString());
        pdc.set(modifiersKey, PersistentDataType.STRING, encode(wand.modifiers));
        pdc.set(new NamespacedKey(plugin, "sellwand_uses"), PersistentDataType.INTEGER, wand.lifetime.uses());
        pdc.set(new NamespacedKey(plugin, "sellwand_expires"), PersistentDataType.LONG, wand.lifetime.expiresAt());
        item.setItemMeta(meta);
        return item;
    }

    /** Convenience for writing this exact value; equivalent to {@code write(this)}. */
    public ItemStack write() {
        return write(this);
    }

    /**
     * Encodes only known effects, in enum order, so the stored string is stable
     * regardless of map iteration order.
     */
    private String encode(Map<String, Modifier> source) {
        StringBuilder out = new StringBuilder();
        for (ModifierType.Effect effect : ModifierType.Effect.values()) {
            Modifier modifier = source.get(effect.storageKey());
            if (modifier == null) continue;
            if (!out.isEmpty()) out.append(';');
            out.append(effect.storageKey()).append('=').append(Double.toString(modifier.value()));
        }
        return out.toString();
    }

    private Map<String, Modifier> decode(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        Map<String, Modifier> out = new LinkedHashMap<>();
        for (String part : raw.split(";")) {
            if (part.isBlank()) continue;
            int split = part.indexOf('=');
            if (split <= 0 || split >= part.length() - 1) continue;

            ModifierType.Effect effect = ModifierType.Effect.fromStorageKey(part.substring(0, split));
            if (effect == null) continue;
            ModifierType type = types.get(effect);
            if (type == null) continue;

            double value;
            try {
                value = Double.parseDouble(part.substring(split + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (!Double.isFinite(value)) continue;
            out.put(effect.storageKey(), Modifier.of(type, value));
        }
        return out;
    }

    /** Copy of the applied modifiers keyed by stable effect key, in enum order. */
    private static Map<String, Modifier> canonical(Map<String, Modifier> source) {
        Map<String, Modifier> out = new LinkedHashMap<>();
        if (source != null) {
            for (ModifierType.Effect effect : ModifierType.Effect.values()) {
                Modifier modifier = source.get(effect.storageKey());
                if (modifier != null) out.put(effect.storageKey(), modifier);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Stable identity of this wand; preserved by {@link #write(Wand)}. */
    public UUID id() { return id; }

    public WandDefinition definition() { return definition; }

    /** Applied modifiers keyed by stable effect key; unmodifiable. */
    public Map<String, Modifier> modifiers() { return modifiers; }

    public Modifier modifier(ModifierType.Effect effect) {
        return effect == null ? null : modifiers.get(effect.storageKey());
    }

    public boolean hasModifier(ModifierType.Effect effect) {
        return modifier(effect) != null;
    }

    public int modifierCount() { return modifiers.size(); }

    /** Returns a copy with this modifier replacing any value of the same effect. */
    public Wand with(Modifier modifier) {
        if (modifier == null) return this;
        Map<String, Modifier> next = new LinkedHashMap<>(modifiers);
        next.put(modifier.storageKey(), modifier);
        return new Wand(plugin, definition, types, id, next, lifetime);
    }

    /** The wand's current use budget or expiry. */
    public WandLifetime lifetime() { return lifetime; }

    /** Returns the same wand identity with a new lifetime (grant or use spent). */
    public Wand withLifetime(WandLifetime lifetime) {
        return new Wand(plugin, definition, types, id, modifiers, lifetime);
    }

    /** Spends one use from a counted wand; infinite and day wands are unchanged. */
    public Wand consumeUse() { return withLifetime(lifetime.consume()); }

    /** Configured radius plus every range modifier on the wand, in blocks. */
    public double radius() {
        double radius = definition.radius();
        Modifier range = modifier(ModifierType.Effect.RANGE);
        if (range != null) radius += range.value();
        return Math.max(0.0, radius);
    }

    /**
     * Product of every price modifier, never below one.
     *
     * <p>A sub-one modifier is clamped so applying an upgrade cannot reduce the
     * quote. The resulting multiplier is passed into settlement before payment.
     */
    public double priceMultiplier() {
        double multiplier = 1.0;
        Modifier price = modifier(ModifierType.Effect.PRICE_MULTIPLIER);
        if (price != null) multiplier *= price.value();
        return Math.max(1.0, multiplier);
    }
}
