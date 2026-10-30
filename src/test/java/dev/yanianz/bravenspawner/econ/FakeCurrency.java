package dev.yanianz.bravenspawner.econ;

import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.List;

/** Scriptable currency for tests: records every deposit/withdraw call. */
public final class FakeCurrency implements Currency {
    public final String id;
    public boolean available = true;
    public boolean whole;
    public boolean acceptDeposits = true;
    public boolean acceptWithdrawals = true;
    public double balance;
    public final List<Double> deposits = new ArrayList<>();
    public final List<Double> withdrawals = new ArrayList<>();

    public FakeCurrency(String id) { this.id = id; }

    @Override public String id() { return id; }
    @Override public String displayName() { return "Fake " + id; }
    @Override public String symbol() { return "F"; }
    @Override public boolean available() { return available; }
    @Override public String unavailableReason() { return available ? "" : "fake is offline"; }
    @Override public boolean wholeUnits() { return whole; }
    @Override public double balance(OfflinePlayer player) { return balance; }

    @Override public Result withdraw(OfflinePlayer player, double amount) {
        withdrawals.add(amount);
        if (!acceptWithdrawals || balance < amount) return Result.fail("no");
        balance -= amount;
        return Result.ok(amount);
    }

    @Override public Result deposit(OfflinePlayer player, double amount) {
        deposits.add(amount);
        if (!acceptDeposits) return Result.fail("fake rejected the deposit");
        balance += amount;
        return Result.ok(amount);
    }

    @Override public String format(double amount) { return "F" + amount; }
}
