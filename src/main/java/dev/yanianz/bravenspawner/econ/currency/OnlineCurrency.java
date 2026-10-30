package dev.yanianz.bravenspawner.econ.currency;

import dev.yanianz.bravenspawner.econ.AbstractCurrency;
import dev.yanianz.bravenspawner.econ.CurrencyFormat;
import dev.yanianz.bravenspawner.econ.CurrencyFormats;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Built-in currencies stored on the player entity (experience, inventory items).
 *
 * <p>They exist only while the player is online and may only be touched from
 * the thread that owns the player (the main thread on Paper, the entity's
 * region on Folia). An offline owner, e.g. during autosell, gets a refusal and
 * the sale keeps its stock.
 */
abstract class OnlineCurrency extends AbstractCurrency {

    OnlineCurrency(String id, String name, CurrencyFormat format, CurrencyFormats formats) {
        super(id, name, format, formats);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean requiresOnline() {
        return true;
    }

    @Override
    public boolean wholeUnits() {
        return true;
    }

    /** The online player, or null when offline or touched from a foreign thread. */
    static Player owned(OfflinePlayer player) {
        if (player == null) return null;
        Player online = player instanceof Player direct ? direct : player.getPlayer();
        if (online == null || !online.isOnline()) return null;
        boolean owned;
        try {
            owned = Bukkit.isOwnedByCurrentRegion(online);
        } catch (NoSuchMethodError | RuntimeException legacy) {
            owned = Bukkit.isPrimaryThread();
        }
        return owned ? online : null;
    }

    static String refusal(OfflinePlayer player) {
        Player online = player == null ? null : player.getPlayer();
        return online == null || !online.isOnline() ? "the player must be online" : "not on the player's thread";
    }

    @Override
    public final double balance(OfflinePlayer player) {
        Player online = owned(player);
        return online == null ? 0.0 : balanceOf(online);
    }

    @Override
    protected final Result take(OfflinePlayer player, double amount) {
        Player online = owned(player);
        return online == null ? Result.fail(refusal(player)) : takeFrom(online, amount);
    }

    @Override
    protected final Result give(OfflinePlayer player, double amount) {
        Player online = owned(player);
        return online == null ? Result.fail(refusal(player)) : giveTo(online, amount);
    }

    abstract double balanceOf(Player player);

    abstract Result takeFrom(Player player, double amount);

    abstract Result giveTo(Player player, double amount);
}
