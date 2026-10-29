package dev.yanianz.bravemspawner.econ;

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

    public void deposit(OfflinePlayer player, double amount) {
        if (amount > 0 && provider != null) provider.depositPlayer(player, amount);
    }

    public String format(double amount) {
        return provider == null
                ? String.format(java.util.Locale.ROOT, "%.2f", amount)
                : provider.format(amount);
    }
}
