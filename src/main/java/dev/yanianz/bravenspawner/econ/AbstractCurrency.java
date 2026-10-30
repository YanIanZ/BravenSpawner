package dev.yanianz.bravenspawner.econ;

import org.bukkit.OfflinePlayer;

import java.util.Objects;

/**
 * Shared id/format handling and the non-positive-amount rule for providers.
 *
 * <p>Subclasses implement only {@link #take} and {@link #give}; this class
 * guarantees they never see a zero, negative or non-finite amount and that an
 * unavailable provider is never asked to move money.
 */
public abstract class AbstractCurrency implements Currency {

    private final String id;
    private final String defaultName;
    private final CurrencyFormat defaultFormat;
    private final CurrencyFormats formats;

    protected AbstractCurrency(String id, String defaultName, CurrencyFormat defaultFormat, CurrencyFormats formats) {
        this.id = Objects.requireNonNull(id, "id");
        this.defaultName = Objects.requireNonNull(defaultName, "defaultName");
        this.defaultFormat = Objects.requireNonNull(defaultFormat, "defaultFormat");
        this.formats = formats;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public String displayName() {
        if (formats != null) {
            var economy = formats.economy();
            var section = economy == null ? null : economy.getConfigurationSection("format");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    if (CurrencyRegistry.normalize(key).equals(CurrencyRegistry.normalize(id))) {
                        String name = section.getString(key + ".name", "");
                        if (name != null && !name.isBlank()) return name;
                    }
                }
            }
        }
        return defaultName;
    }

    @Override
    public String symbol() {
        return formatSpec().shortSymbol();
    }

    protected CurrencyFormat formatSpec() {
        return formats == null ? defaultFormat : formats.resolve(id, defaultFormat);
    }

    @Override
    public String format(double amount) {
        return formatSpec().format(amount, formats == null ? null : formats.money());
    }

    @Override
    public final Result withdraw(OfflinePlayer player, double amount) {
        if (!Double.isFinite(amount)) return Result.fail("invalid amount");
        if (amount <= 0) return Result.ok(0.0);
        if (player == null) return Result.fail("no player");
        if (!available()) return Result.fail(unavailableReason());
        try {
            return take(player, amount);
        } catch (RuntimeException | LinkageError error) {
            return Result.fail(displayName() + " withdraw failed: " + error.getMessage());
        }
    }

    @Override
    public final Result deposit(OfflinePlayer player, double amount) {
        if (!Double.isFinite(amount)) return Result.fail("invalid amount");
        if (amount <= 0) return Result.ok(0.0);
        if (player == null) return Result.fail("no player");
        if (!available()) return Result.fail(unavailableReason());
        try {
            return give(player, amount);
        } catch (RuntimeException | LinkageError error) {
            return Result.fail(displayName() + " deposit failed: " + error.getMessage());
        }
    }

    protected abstract Result take(OfflinePlayer player, double amount);

    protected abstract Result give(OfflinePlayer player, double amount);

    @Override
    public String toString() {
        return id;
    }
}
