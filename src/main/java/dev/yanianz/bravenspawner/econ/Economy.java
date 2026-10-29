package dev.yanianz.bravenspawner.econ;

import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault bridge with no reflection: the service is resolved through Bukkit's own
 * service registry, and a missing Vault simply leaves {@link #enabled()} false.
 */
public final class Economy {

    private net.milkbowl.vault.economy.Economy provider;

    public boolean enabled() {
        return provider != null;
    }

    public void hook() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> registration =
                Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        if (registration != null) provider = registration.getProvider();
    }

    public double balance(OfflinePlayer player) {
        return provider == null ? 0.0 : provider.getBalance(player);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (amount <= 0) return true;
        if (provider == null) return false;
        EconomyResponse response = provider.withdrawPlayer(player, amount);
        return response != null && response.transactionSuccess();
    }

    /**
     * Credits the player and reports whether the provider confirmed it.
     *
     * <p>Vault answers with an {@link EconomyResponse}, and a provider may reject
     * or partially fail a deposit without throwing. Returning the response's own
     * {@code transactionSuccess()} is what lets the caller keep stock it has not
     * been paid for; swallowing the response made every non-zero amount look paid.
     * A missing response is treated as a failure — fail closed.
     */
    public boolean deposit(OfflinePlayer player, double amount) {
        if (amount <= 0) return true;
        if (player == null || provider == null) return false;
        EconomyResponse response = provider.depositPlayer(player, amount);
        if (response == null) return false;
        if (!response.transactionSuccess()) {
            Bukkit.getLogger().warning("Vault deposit of " + amount + " for "
                    + player.getUniqueId() + " was rejected: " + response.errorMessage);
            return false;
        }
        return true;
    }

    public String format(double amount) {
        return provider == null
                ? String.format(java.util.Locale.ROOT, "%.2f", amount)
                : provider.format(amount);
    }
}
