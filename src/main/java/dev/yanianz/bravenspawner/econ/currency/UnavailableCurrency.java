package dev.yanianz.bravenspawner.econ.currency;

import dev.yanianz.bravenspawner.econ.AbstractCurrency;
import dev.yanianz.bravenspawner.econ.CurrencyFormat;
import dev.yanianz.bravenspawner.econ.CurrencyFormats;
import org.bukkit.OfflinePlayer;

/**
 * Stand-in for a configured currency that cannot be used: unknown id, missing
 * plugin, or {@code economy.enabled: false}. Every transaction fails, so a
 * misconfiguration can never make an action free.
 */
public final class UnavailableCurrency extends AbstractCurrency {

    private final String name;
    private final String reason;

    public UnavailableCurrency(String id, String name, String reason, CurrencyFormats formats) {
        super(id, name, CurrencyFormat.of("", "", -1), formats);
        this.name = name;
        this.reason = reason;
    }

    @Override
    public String displayName() {
        return name;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String unavailableReason() {
        return reason;
    }

    @Override
    public boolean wholeUnits() {
        return false;
    }

    @Override
    public double balance(OfflinePlayer player) {
        return 0.0;
    }

    @Override
    protected Result take(OfflinePlayer player, double amount) {
        return Result.fail(reason);
    }

    @Override
    protected Result give(OfflinePlayer player, double amount) {
        return Result.fail(reason);
    }
}
