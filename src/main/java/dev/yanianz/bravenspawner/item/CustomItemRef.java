package dev.yanianz.bravenspawner.item;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A drop that names an item from a custom-item plugin, written {@code provider:key}
 * in a product's {@code material} field.
 *
 * <p>Only known provider prefixes are recognised, so a vanilla namespaced key such
 * as {@code minecraft:diamond} is never mistaken for one. MMOItems keys are
 * {@code TYPE:ID} and are upper-cased the way MMOItems stores them; every other
 * provider's id is kept exactly as written because those registries are
 * case-sensitive.
 */
public record CustomItemRef(String provider, String key) {

    public static final String MMOITEMS = "mmoitems";
    public static final String ECOITEMS = "ecoitems";
    public static final String ITEMSADDER = "itemsadder";
    public static final String ORAXEN = "oraxen";
    public static final String NEXO = "nexo";
    public static final String MYTHICMOBS = "mythicmobs";

    private static final Map<String, String> PREFIXES = Map.ofEntries(
            Map.entry("mmoitems", MMOITEMS), Map.entry("mmoitem", MMOITEMS),
            Map.entry("ecoitems", ECOITEMS), Map.entry("ecoitem", ECOITEMS),
            Map.entry("itemsadder", ITEMSADDER), Map.entry("ia", ITEMSADDER),
            Map.entry("oraxen", ORAXEN),
            Map.entry("nexo", NEXO),
            Map.entry("mythicmobs", MYTHICMOBS), Map.entry("mythic", MYTHICMOBS),
            Map.entry("crucible", MYTHICMOBS));

    public CustomItemRef {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) throw new IllegalArgumentException("key must not be blank");
    }

    /** True when {@code raw} starts with a custom-item provider prefix, valid or not. */
    public static boolean hasProviderPrefix(String raw) {
        if (raw == null) return false;
        int colon = raw.indexOf(':');
        return colon > 0 && PREFIXES.containsKey(raw.substring(0, colon).trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Parses {@code provider:key}.
     *
     * @return the reference, or {@code null} when {@code raw} has no known prefix
     * @throws IllegalArgumentException when the prefix is known but the key is unusable
     */
    public static CustomItemRef parse(String raw) {
        if (!hasProviderPrefix(raw)) return null;
        String text = raw.trim();
        int colon = text.indexOf(':');
        String provider = PREFIXES.get(text.substring(0, colon).trim().toLowerCase(Locale.ROOT));
        String key = text.substring(colon + 1).trim();
        if (key.isEmpty()) throw new IllegalArgumentException("'" + raw + "' names no item after the prefix");
        if (provider.equals(MMOITEMS)) {
            String[] parts = key.split(":", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("'" + raw + "' must be mmoitems:TYPE:ID");
            }
            key = parts[0].trim().toUpperCase(Locale.ROOT) + ":" + parts[1].trim().toUpperCase(Locale.ROOT);
        }
        return new CustomItemRef(provider, key);
    }

    /** MMOItems type, or {@code null} for other providers. */
    String mmoType() {
        return provider.equals(MMOITEMS) ? key.substring(0, key.indexOf(':')) : null;
    }

    /** MMOItems id, or {@code null} for other providers. */
    String mmoId() {
        return provider.equals(MMOITEMS) ? key.substring(key.indexOf(':') + 1) : null;
    }

    @Override
    public String toString() {
        return provider + ":" + key;
    }
}
