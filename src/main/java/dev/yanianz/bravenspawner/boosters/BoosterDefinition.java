package dev.yanianz.bravenspawner.boosters;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Configuration for one authenticated item; values remain server controlled after item creation. */
public record BoosterDefinition(String id, Boosters.Scope scope, Boosters.Type type, double multiplier,
                                long seconds, String duration, Material material, String name, List<String> lore) {
    public BoosterDefinition { lore = List.copyOf(lore); }

    public static Map<String, BoosterDefinition> load(JavaPlugin plugin, Boosters.Scope scope) {
        String resource = "boosters/" + scope.name().toLowerCase(Locale.ROOT) + ".yml";
        File file = new File(plugin.getDataFolder(), resource);
        if (!file.exists()) {
            try { plugin.saveResource(resource, false); }
            catch (IllegalArgumentException exception) { plugin.getLogger().warning("Missing " + resource + ": " + exception.getMessage()); }
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<String, BoosterDefinition> definitions = new LinkedHashMap<>();
        for (String key : yaml.getKeys(false)) {
            var section = yaml.getConfigurationSection(key);
            if (section == null) continue;
            try {
                Boosters.Type type = switch (section.getString("type", "").toUpperCase(Locale.ROOT)) {
                    case "INCREASE_SPEED", "SPEED" -> Boosters.Type.SPEED;
                    case "INCREASE_XP", "XP_MULTIPLIER", "XP" -> Boosters.Type.XP;
                    case "INCREASE_LOOT", "LOOT" -> Boosters.Type.LOOT;
                    case "AUTO_SELL", "AUTOSELL" -> Boosters.Type.AUTOSELL;
                    case "CASH_MULTIPLIER", "CASH" -> Boosters.Type.CASH;
                    default -> throw new IllegalArgumentException("unknown type");
                };
                if (scope == Boosters.Scope.PERSONAL && type != Boosters.Type.XP && type != Boosters.Type.CASH) {
                    throw new IllegalArgumentException("personal items support XP and CASH only");
                }
                if (scope == Boosters.Scope.NORMAL && type == Boosters.Type.CASH) {
                    throw new IllegalArgumentException("CASH is a personal effect");
                }
                double multiplier = section.getDouble("multiplier", Double.NaN);
                if (!Double.isFinite(multiplier) || (type == Boosters.Type.AUTOSELL ? multiplier < 0 : multiplier <= 0)) {
                    throw new IllegalArgumentException("invalid multiplier");
                }
                String duration = scope == Boosters.Scope.PERSONAL ? "Permanent" : section.getString("expiration-time", "1d");
                long seconds = scope == Boosters.Scope.PERSONAL ? -1 : durationSeconds(duration);
                Material material = Material.matchMaterial(section.getString("item.material", "PAPER"));
                if (material == null || material.isAir()) throw new IllegalArgumentException("invalid item material");
                String id = key.toLowerCase(Locale.ROOT);
                definitions.put(id, new BoosterDefinition(id, scope, type, multiplier, seconds, duration, material,
                        section.getString("item.name", section.getString("display-name", key)), section.getStringList("item.lore")));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning(resource + " entry " + key + " skipped: " + exception.getMessage());
            }
        }
        return Map.copyOf(definitions);
    }

    public static long durationSeconds(String raw) {
        if (raw == null) throw new IllegalArgumentException("duration missing");
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.equals("inf") || text.equals("infinite") || text.equals("-1")) return -1;
        try {
            char suffix = text.charAt(text.length() - 1);
            long unit = switch (suffix) { case 's' -> 1; case 'm' -> 60; case 'h' -> 3600; case 'd' -> 86400; default -> 0; };
            long value = Long.parseLong(unit == 0 ? text : text.substring(0, text.length() - 1));
            long seconds = Math.multiplyExact(value, unit == 0 ? 1 : unit);
            if (seconds <= 0) throw new IllegalArgumentException("duration must be positive");
            Math.addExact(System.currentTimeMillis(), Math.multiplyExact(seconds, 1000L));
            return seconds;
        } catch (ArithmeticException | IndexOutOfBoundsException exception) {
            throw new IllegalArgumentException("invalid or overflowing duration", exception);
        }
    }
}
