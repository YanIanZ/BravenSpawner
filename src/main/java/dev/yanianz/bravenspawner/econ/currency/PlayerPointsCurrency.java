package dev.yanianz.bravenspawner.econ.currency;

import dev.yanianz.bravenspawner.econ.AbstractCurrency;
import dev.yanianz.bravenspawner.econ.CurrencyFormat;
import dev.yanianz.bravenspawner.econ.CurrencyFormats;
import dev.yanianz.bravenspawner.econ.CurrencyMath;
import dev.yanianz.bravenspawner.econ.Reflection;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * PlayerPoints (Rosewood) integer points, reached by reflection.
 *
 * <p>API shape verified against {@code org.black_ixx.playerpoints.PlayerPointsAPI}
 * on GitHub (Rosewood-Development/PlayerPoints, master): the plugin instance
 * exposes {@code getAPI()}; the API has {@code int look(UUID)},
 * {@code boolean give(UUID,int)} and {@code boolean take(UUID,int)}. All are
 * UUID based, so offline players work.
 */
public final class PlayerPointsCurrency extends AbstractCurrency {

    public static final String ID = "PLAYERPOINTS";

    private volatile Plugin boundPlugin;
    private volatile Api api;

    private record Api(Object handle, Method look, Method give, Method take) {}

    public PlayerPointsCurrency(CurrencyFormats formats) {
        super(ID, "Points", CurrencyFormat.of("", " points", 0), formats);
    }

    private Api api() {
        Plugin plugin = Reflection.enabledPlugin("PlayerPoints");
        if (plugin == null) {
            api = null;
            boundPlugin = null;
            return null;
        }
        Api cached = api;
        if (cached != null && boundPlugin == plugin) return cached;
        try {
            Object handle = Reflection.invoke(Reflection.method(plugin.getClass(), "getAPI"), plugin);
            if (handle == null) return null;
            Class<?> type = Reflection.load(plugin, "org.black_ixx.playerpoints.PlayerPointsAPI");
            Api built = new Api(handle,
                    Reflection.method(type, "look", UUID.class),
                    Reflection.method(type, "give", UUID.class, int.class),
                    Reflection.method(type, "take", UUID.class, int.class));
            api = built;
            boundPlugin = plugin;
            return built;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return null;
        }
    }

    @Override
    public boolean available() {
        return api() != null;
    }

    @Override
    public String unavailableReason() {
        if (Reflection.enabledPlugin("PlayerPoints") == null) return "PlayerPoints is not installed or not enabled";
        return available() ? "" : "PlayerPoints is installed but its API could not be reached";
    }

    @Override
    public boolean wholeUnits() {
        return true;
    }

    @Override
    public double balance(OfflinePlayer player) {
        Api current = api();
        if (current == null || player == null) return 0.0;
        try {
            return Reflection.asDouble(Reflection.invoke(current.look(), current.handle(), player.getUniqueId()));
        } catch (RuntimeException error) {
            return 0.0;
        }
    }

    @Override
    protected Result take(OfflinePlayer player, double amount) {
        Api current = api();
        int points = CurrencyMath.toWholeUnits(Math.ceil(amount - 1e-9));
        if (balance(player) < points) return Result.fail("not enough points");
        boolean ok = Reflection.asBoolean(Reflection.invoke(current.take(), current.handle(), player.getUniqueId(), points));
        return ok ? Result.ok(points) : Result.fail("PlayerPoints rejected the withdrawal");
    }

    @Override
    protected Result give(OfflinePlayer player, double amount) {
        Api current = api();
        int points = CurrencyMath.toWholeUnits(amount);
        if (points <= 0) return Result.fail("amount is below one point");
        boolean ok = Reflection.asBoolean(Reflection.invoke(current.give(), current.handle(), player.getUniqueId(), points));
        return ok ? Result.ok(points) : Result.fail("PlayerPoints rejected the deposit");
    }
}
