package dev.yanianz.bravenspawner.econ.currency;

import dev.yanianz.bravenspawner.econ.AbstractCurrency;
import dev.yanianz.bravenspawner.econ.CurrencyFormat;
import dev.yanianz.bravenspawner.econ.CurrencyFormats;
import dev.yanianz.bravenspawner.econ.Economy;
import org.bukkit.OfflinePlayer;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Vault money through the existing {@link Economy} bridge.
 *
 * <p>Economy plugins register their Vault service when they enable, which may
 * be after this plugin. When {@code mayHook} is true an unhooked bridge retries
 * the cheap service lookup, so a late provider becomes usable without restart.
 */
public final class VaultCurrency extends AbstractCurrency {

    public static final String ID = "VAULT";

    private final Supplier<Economy> economy;
    private final BooleanSupplier mayHook;

    public VaultCurrency(Supplier<Economy> economy, BooleanSupplier mayHook, CurrencyFormats formats) {
        super(ID, "Money", CurrencyFormat.of("", "", -1), formats);
        this.economy = economy;
        this.mayHook = mayHook;
    }

    @Override
    public boolean available() {
        Economy bridge = economy.get();
        if (bridge == null) return false;
        if (!bridge.enabled() && mayHook.getAsBoolean()) {
            try {
                bridge.hook();
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }
        return bridge.enabled();
    }

    @Override
    public String unavailableReason() {
        return available() ? "" : "no Vault economy provider is registered";
    }

    @Override
    public boolean wholeUnits() {
        return false;
    }

    @Override
    public double balance(OfflinePlayer player) {
        Economy bridge = economy.get();
        if (player == null || bridge == null || !bridge.enabled()) return 0.0;
        try {
            return bridge.balance(player);
        } catch (RuntimeException error) {
            return 0.0;
        }
    }

    @Override
    protected Result take(OfflinePlayer player, double amount) {
        return economy.get().withdraw(player, amount) ? Result.ok(amount) : Result.fail("Vault rejected the withdrawal");
    }

    @Override
    protected Result give(OfflinePlayer player, double amount) {
        return economy.get().deposit(player, amount) ? Result.ok(amount) : Result.fail("Vault rejected the deposit");
    }
}
