package dev.yanianz.bravenspawner.econ;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Live view of {@code economy.format.<currency>} and {@code money-decimals}.
 *
 * <p>Read on every call because {@code reloadConfig()} replaces the config
 * object; a cached format would keep last boot's symbol after a reload.
 */
public final class CurrencyFormats {

    private final Supplier<ConfigurationSection> root;

    public CurrencyFormats(Supplier<ConfigurationSection> root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public static CurrencyFormats of(Plugin plugin) {
        return new CurrencyFormats(plugin::getConfig);
    }

    /** {@code economy} section, or null. */
    public ConfigurationSection economy() {
        ConfigurationSection config = root.get();
        return config == null ? null : config.getConfigurationSection("economy");
    }

    public CurrencyFormat resolve(String id, CurrencyFormat fallback) {
        ConfigurationSection economy = economy();
        ConfigurationSection formats = economy == null ? null : economy.getConfigurationSection("format");
        if (formats == null || id == null) return fallback;
        for (String key : formats.getKeys(false)) {
            if (CurrencyRegistry.normalize(key).equals(CurrencyRegistry.normalize(id))) {
                return CurrencyFormat.read(formats.getConfigurationSection(key), fallback);
            }
        }
        return fallback;
    }

    public CurrencyFormat.MoneyStyle money() {
        ConfigurationSection config = root.get();
        if (config == null) return CurrencyFormat.MoneyStyle.DEFAULT;
        List<String> defaults = CurrencyFormat.MoneyStyle.DEFAULT.formats();
        List<String> formats = new ArrayList<>(defaults.size());
        ConfigurationSection section = config.getConfigurationSection("money-decimals.formats");
        for (int i = 1; i <= defaults.size(); i++) {
            String fallback = defaults.get(i - 1);
            formats.add(section == null ? fallback : section.getString(String.valueOf(i), fallback));
        }
        return new CurrencyFormat.MoneyStyle(
                config.getBoolean("money-decimals.enabled", true),
                config.getBoolean("money-decimals.apply-on-amount", true),
                List.copyOf(formats));
    }
}
