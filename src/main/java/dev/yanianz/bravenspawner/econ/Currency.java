package dev.yanianz.bravenspawner.econ;

import org.bukkit.OfflinePlayer;

/**
 * One way of paying or charging a player.
 *
 * <p>Every provider answers the same small set of questions so the sell,
 * buy-stack and upgrade paths never need to know whether money comes from Vault,
 * a points plugin, experience or physical items. A provider that cannot act
 * right now reports {@link #available()} as false and every transaction fails;
 * callers must turn that into a refusal, never into a free action.
 */
public interface Currency {

    /** Stable id used in config, e.g. {@code VAULT} or {@code coinsengine:gems}. */
    String id();

    /** Human name used for {@code %currency%}. */
    String displayName();

    /** Short symbol used for {@code %currency_symbol%}; may be empty. */
    String symbol();

    /** True when a transaction could be attempted now. */
    boolean available();

    /** One line explaining why {@link #available()} is false, for logs and doctor. */
    default String unavailableReason() {
        return available() ? "" : displayName() + " is not available";
    }

    /** True when balances live on the online player entity (experience, items). */
    default boolean requiresOnline() {
        return false;
    }

    /** True when the currency only has whole units (points, levels, items). */
    boolean wholeUnits();

    /** Current balance; zero when it cannot be read. */
    double balance(OfflinePlayer player);

    default boolean has(OfflinePlayer player, double amount) {
        if (amount <= 0) return true;
        return balance(player) + 1e-9 >= amount;
    }

    /** Takes {@code amount}; a non-positive amount succeeds without touching the provider. */
    Result withdraw(OfflinePlayer player, double amount);

    /** Gives {@code amount}; a non-positive amount succeeds without touching the provider. */
    Result deposit(OfflinePlayer player, double amount);

    /** Amount text including symbol/suffix from {@code economy.format}. */
    String format(double amount);

    /** Outcome of one transaction. {@code error} is empty on success. */
    record Result(boolean success, double amount, String error) {
        public static Result ok(double amount) { return new Result(true, amount, ""); }
        public static Result fail(String error) { return new Result(false, 0.0, error == null ? "" : error); }
    }
}
