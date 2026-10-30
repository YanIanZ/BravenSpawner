package dev.yanianz.bravenspawner.particles;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One configured particle: type, data, amount and spread.
 *
 * <p>Names are resolved defensively because particle names changed in 1.20.5
 * (for example {@code REDSTONE} became {@code DUST}) and newer servers may add or
 * rename more: the old names in {@link #ALIASES} keep working, a namespaced key
 * is looked up in the registry, and an unknown name disables the effect with a
 * single warning instead of throwing on every cycle.
 *
 * <p>Instances are immutable; {@link #show} must run on the thread that owns the
 * location (the spawner's region on Folia).
 */
public final class ParticleSpec {

    /** Pre-1.20.5 names mapped to their current constant. */
    static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("REDSTONE", "DUST"),
            Map.entry("SPELL_MOB", "ENTITY_EFFECT"),
            Map.entry("SPELL_MOB_AMBIENT", "ENTITY_EFFECT"),
            Map.entry("SPELL", "EFFECT"),
            Map.entry("SPELL_INSTANT", "INSTANT_EFFECT"),
            Map.entry("SPELL_WITCH", "WITCH"),
            Map.entry("VILLAGER_HAPPY", "HAPPY_VILLAGER"),
            Map.entry("VILLAGER_ANGRY", "ANGRY_VILLAGER"),
            Map.entry("FIREWORKS_SPARK", "FIREWORK"),
            Map.entry("ENCHANTMENT_TABLE", "ENCHANT"),
            Map.entry("TOTEM", "TOTEM_OF_UNDYING"),
            Map.entry("SMOKE_NORMAL", "SMOKE"),
            Map.entry("SMOKE_LARGE", "LARGE_SMOKE"),
            Map.entry("CRIT_MAGIC", "ENCHANTED_HIT"),
            Map.entry("EXPLOSION_NORMAL", "POOF"),
            Map.entry("EXPLOSION_LARGE", "EXPLOSION"),
            Map.entry("EXPLOSION_HUGE", "EXPLOSION_EMITTER"),
            Map.entry("WATER_SPLASH", "SPLASH"),
            Map.entry("WATER_BUBBLE", "BUBBLE"),
            Map.entry("WATER_WAKE", "FISHING"),
            Map.entry("SUSPENDED", "UNDERWATER"),
            Map.entry("DRIP_WATER", "DRIPPING_WATER"),
            Map.entry("DRIP_LAVA", "DRIPPING_LAVA"),
            Map.entry("SLIME", "ITEM_SLIME"),
            Map.entry("SNOWBALL", "ITEM_SNOWBALL"),
            Map.entry("SNOW_SHOVEL", "ITEM_SNOWBALL"),
            Map.entry("BLOCK_CRACK", "BLOCK"),
            Map.entry("BLOCK_DUST", "BLOCK"),
            Map.entry("ITEM_CRACK", "ITEM"),
            Map.entry("MOB_APPEARANCE", "ELDER_GUARDIAN"),
            Map.entry("TOWN_AURA", "MYCELIUM"));

    private static final Map<String, Color> COLORS = Map.ofEntries(
            Map.entry("WHITE", Color.WHITE), Map.entry("SILVER", Color.SILVER), Map.entry("GRAY", Color.GRAY),
            Map.entry("GREY", Color.GRAY), Map.entry("BLACK", Color.BLACK), Map.entry("RED", Color.RED),
            Map.entry("MAROON", Color.MAROON), Map.entry("YELLOW", Color.YELLOW), Map.entry("OLIVE", Color.OLIVE),
            Map.entry("LIME", Color.LIME), Map.entry("GREEN", Color.GREEN), Map.entry("AQUA", Color.AQUA),
            Map.entry("TEAL", Color.TEAL), Map.entry("BLUE", Color.BLUE), Map.entry("NAVY", Color.NAVY),
            Map.entry("FUCHSIA", Color.FUCHSIA), Map.entry("PURPLE", Color.PURPLE), Map.entry("ORANGE", Color.ORANGE),
            Map.entry("GOLD", Color.fromRGB(0xFFAA00)), Map.entry("PINK", Color.fromRGB(0xFF69B4)));

    /** Fallback values for a section; every key in the section overrides one of these. */
    public record Defaults(boolean enabled, String type, int count, double offset, double speed,
                           double viewDistance, String color, float size) { }

    private final Particle particle;
    private final Object data;
    private final int count;
    private final double offset;
    private final double speed;
    private final double viewDistance;

    ParticleSpec(Particle particle, Object data, int count, double offset, double speed, double viewDistance) {
        this.particle = particle;
        this.data = data;
        this.count = count;
        this.offset = offset;
        this.speed = speed;
        this.viewDistance = viewDistance;
    }

    public Particle particle() { return particle; }
    public Object data() { return data; }
    public int count() { return count; }
    public double offset() { return offset; }
    public double speed() { return speed; }
    public double viewDistance() { return viewDistance; }

    /**
     * Reads one section.
     *
     * @param path used in warnings only
     * @return the spec, or null when disabled or unusable (after one warning)
     */
    public static ParticleSpec read(ConfigurationSection section, String path, Defaults defaults, Consumer<String> warn) {
        boolean enabled = section == null ? defaults.enabled() : section.getBoolean("enabled", defaults.enabled());
        if (!enabled) return null;
        String type = section == null ? defaults.type() : section.getString("type", defaults.type());
        Particle particle = resolve(type);
        if (particle == null) {
            warn.accept(path + ".type '" + type + "' is not a particle on this server; the effect is disabled.");
            return null;
        }
        String colorName = section == null ? defaults.color() : section.getString("color", defaults.color());
        Color color = color(colorName);
        if (color == null) {
            warn.accept(path + ".color '" + colorName + "' is not a colour; using white.");
            color = Color.WHITE;
        }
        float size = (float) clamp(section == null ? defaults.size() : section.getDouble("size", defaults.size()), 0.1, 4.0);
        Object data;
        try {
            data = data(particle, color, size);
        } catch (UnsupportedOperationException e) {
            warn.accept(path + ".type '" + type + "' needs data this plugin cannot supply ("
                    + e.getMessage() + "); the effect is disabled.");
            return null;
        }
        int count = (int) clamp(section == null ? defaults.count() : section.getInt("count", defaults.count()), 1, 200);
        double offset = clamp(section == null ? defaults.offset() : section.getDouble("offset", defaults.offset()), 0.0, 4.0);
        double speed = clamp(section == null ? defaults.speed() : section.getDouble("speed", defaults.speed()), 0.0, 4.0);
        double view = clamp(section == null ? defaults.viewDistance()
                : section.getDouble("view-distance", defaults.viewDistance()), 1.0, 128.0);
        return new ParticleSpec(particle, data, count, offset, speed, view);
    }

    /** Current constant name for a configured name, applying {@link #ALIASES}. */
    static String canonical(String raw) {
        if (raw == null) return null;
        String name = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (name.startsWith("MINECRAFT:")) name = name.substring("MINECRAFT:".length());
        return ALIASES.getOrDefault(name, name);
    }

    /** Resolves a particle by name or alias; null when this server has no such particle. */
    public static Particle resolve(String raw) {
        String name = canonical(raw);
        if (name == null || name.isEmpty()) return null;
        try {
            return Particle.valueOf(name);
        } catch (IllegalArgumentException | LinkageError notAConstant) {
            try {
                return Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
            } catch (RuntimeException | LinkageError noRegistry) {
                return null;
            }
        }
    }

    /** {@code #RRGGBB}, {@code RRGGBB} or a colour name; null when unrecognised. */
    public static Color color(String raw) {
        if (raw == null || raw.isBlank()) return Color.WHITE;
        String value = raw.trim();
        Color named = COLORS.get(value.toUpperCase(Locale.ROOT));
        if (named != null) return named;
        if (value.startsWith("&#")) value = value.substring(2);
        else if (value.startsWith("#")) value = value.substring(1);
        else if (value.regionMatches(true, 0, "0x", 0, 2)) value = value.substring(2);
        if (!value.matches("[0-9a-fA-F]{6}")) return null;
        return Color.fromRGB(Integer.parseInt(value, 16));
    }

    /** Builds the data object a particle requires; throws for data types this plugin cannot build. */
    static Object data(Particle particle, Color color, float size) {
        Class<?> type = particle.getDataType();
        if (type == null || type == Void.class) return null;
        if (type == Particle.DustOptions.class) return new Particle.DustOptions(color, size);
        if (type == Particle.DustTransition.class) return new Particle.DustTransition(color, color, size);
        if (type == Color.class) return color;
        if (type == Float.class) return 0.0f;
        if (type == Integer.class) return 0;
        if (type == BlockData.class) return Bukkit.createBlockData(Material.STONE);
        if (type == ItemStack.class) return new ItemStack(Material.STONE);
        throw new UnsupportedOperationException(type.getSimpleName());
    }

    /** Shows {@link #count} particles around {@code center} to players within the view distance. */
    public void show(Location center) {
        World world = center == null ? null : center.getWorld();
        if (world == null) return;
        for (Player player : world.getNearbyPlayers(center, viewDistance)) {
            player.spawnParticle(particle, center, count, offset, offset, offset, speed, data);
        }
    }

    /** One particle point, visible to the usual vanilla range; used by shaped effects. */
    public void point(World world, double x, double y, double z) {
        world.spawnParticle(particle, x, y, z, 1, 0.0, 0.0, 0.0, speed, data);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}
