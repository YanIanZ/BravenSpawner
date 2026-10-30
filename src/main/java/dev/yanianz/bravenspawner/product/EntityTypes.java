package dev.yanianz.bravenspawner.product;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Version-safe name resolution for product data.
 *
 * <p>The plugin is compiled against 1.21.1 but runs on newer servers, so no
 * entity or material constant newer than that is ever referenced in code; names
 * come from YAML and are looked up at runtime. The registry lookup is tried
 * first (it is the stable API on newer servers), then the legacy enum lookup,
 * for the name itself and for each historical alias. A name that does not exist
 * on the running server resolves to {@code null} — callers decide how to report.
 *
 * <p>Name normalisation ({@link #entityCandidates}, {@link #materialCandidates})
 * is pure and needs no server, which keeps it unit-testable.
 */
public final class EntityTypes {

    /** Renamed entity types; each maps to the other spelling so old and new servers both resolve. */
    private static final Map<String, String> ENTITY_ALIASES = Map.ofEntries(
            Map.entry("MUSHROOM_COW", "MOOSHROOM"),
            Map.entry("MOOSHROOM", "MUSHROOM_COW"),
            Map.entry("SNOWMAN", "SNOW_GOLEM"),
            Map.entry("SNOW_GOLEM", "SNOWMAN"),
            Map.entry("PIG_ZOMBIE", "ZOMBIFIED_PIGLIN"),
            Map.entry("ZOMBIFIED_PIGLIN", "PIG_ZOMBIE"),
            Map.entry("ENDER_CRYSTAL", "END_CRYSTAL"),
            Map.entry("END_CRYSTAL", "ENDER_CRYSTAL"));

    private static final Map<String, String> MATERIAL_ALIASES = Map.ofEntries(
            Map.entry("SCUTE", "TURTLE_SCUTE"),
            Map.entry("TURTLE_SCUTE", "SCUTE"));

    private EntityTypes() {}

    /**
     * Upper-case, namespace-stripped candidate names for an entity type, primary
     * spelling first. Blank or syntactically invalid input yields an empty list.
     */
    public static List<String> entityCandidates(String raw) {
        return candidates(raw, ENTITY_ALIASES);
    }

    public static List<String> materialCandidates(String raw) {
        return candidates(raw, MATERIAL_ALIASES);
    }

    /** The spawnable entity type on this server, or {@code null} when it does not exist here. */
    public static EntityType entity(String raw) {
        for (String name : entityCandidates(raw)) {
            EntityType type = registryEntity(name);
            if (type == null) type = enumEntity(name);
            if (type != null) return type.isSpawnable() ? type : null;
        }
        return null;
    }

    /** The item material on this server, or {@code null} when unknown, air, or not an item. */
    public static Material material(String raw) {
        for (String name : materialCandidates(raw)) {
            Material material = null;
            try { material = Material.matchMaterial(name); }
            catch (RuntimeException ignored) { /* fall through to the registry */ }
            if (material == null) material = registryMaterial(name);
            if (material != null) return material.isItem() && !material.isAir() ? material : null;
        }
        return null;
    }

    private static List<String> candidates(String raw, Map<String, String> aliases) {
        List<String> out = new ArrayList<>(2);
        String name = normalise(raw);
        if (name == null) return out;
        out.add(name);
        String alias = aliases.get(name);
        if (alias != null) out.add(alias);
        return out;
    }

    static String normalise(String raw) {
        if (raw == null) return null;
        String name = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);
        if (name.isEmpty() || !name.matches("[A-Z0-9_]+")) return null;
        return name;
    }

    private static boolean serverRunning() {
        return Bukkit.getServer() != null;
    }

    private static EntityType registryEntity(String name) {
        if (!serverRunning()) return null;
        try {
            return Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static EntityType enumEntity(String name) {
        try {
            return EntityType.valueOf(name);
        } catch (RuntimeException | LinkageError unknown) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static Material registryMaterial(String name) {
        if (!serverRunning()) return null;
        try {
            return Registry.MATERIAL.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }
}
