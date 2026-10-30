package dev.yanianz.bravenspawner.econ;

import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;

/**
 * How one currency's amounts are written: {@code symbol + number + suffix}.
 *
 * <p>The number reuses the plugin's {@code money-decimals} compaction (1.50K,
 * 2.00M ...). {@code decimals < 0} keeps the legacy {@link Text#money} output
 * exactly, which is the Vault default so existing menus do not change.
 */
public record CurrencyFormat(String symbol, String suffix, int decimals, boolean compact) {

    /** The {@code money-decimals} block from config.yml. */
    public record MoneyStyle(boolean enabled, boolean applyOnAmount, List<String> formats) {
        public static final MoneyStyle DEFAULT = new MoneyStyle(true, true,
                List.of("%number%K", "%number%M", "%number%B", "%number%T", "%number%Q", "%number%Qt"));
    }

    public CurrencyFormat {
        symbol = symbol == null ? "" : symbol;
        suffix = suffix == null ? "" : suffix;
        decimals = Math.max(-1, Math.min(6, decimals));
    }

    public static CurrencyFormat of(String symbol, String suffix, int decimals) {
        return new CurrencyFormat(symbol, suffix, decimals, true);
    }

    /** Values from {@code section}, each missing key taken from {@code fallback}. */
    public static CurrencyFormat read(ConfigurationSection section, CurrencyFormat fallback) {
        if (section == null) return fallback;
        return new CurrencyFormat(
                section.getString("symbol", fallback.symbol()),
                section.getString("suffix", fallback.suffix()),
                section.getInt("decimals", fallback.decimals()),
                section.getBoolean("compact", fallback.compact()));
    }

    public String format(double amount, MoneyStyle style) {
        MoneyStyle money = style == null ? MoneyStyle.DEFAULT : style;
        String number;
        if (decimals < 0) {
            number = compact ? Text.money(amount, money.enabled(), money.applyOnAmount(), money.formats())
                    : String.format(Locale.ROOT, "%.2f", amount);
        } else if (compact && money.enabled() && Math.abs(amount) >= 1000 && !money.formats().isEmpty()) {
            number = Text.money(amount, true, money.applyOnAmount(), money.formats());
        } else {
            number = String.format(Locale.ROOT, "%." + decimals + "f", amount);
        }
        return symbol + number + suffix;
    }

    /** {@code %currency_symbol%}: the prefix symbol, else the trimmed suffix. */
    public String shortSymbol() {
        return symbol.isBlank() ? suffix.trim() : symbol;
    }
}
