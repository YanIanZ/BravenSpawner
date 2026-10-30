package dev.yanianz.bravenspawner.econ.currency;

import dev.yanianz.bravenspawner.econ.AbstractCurrency;
import dev.yanianz.bravenspawner.econ.CurrencyFormat;
import dev.yanianz.bravenspawner.econ.CurrencyFormats;
import dev.yanianz.bravenspawner.econ.Reflection;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * One CoinsEngine / ExcellentEconomy currency ({@code coinsengine:<id>}), by reflection.
 *
 * <p>Two API generations are supported, both verified against public source:
 * <ul>
 *   <li><b>ExcellentEconomy</b> (CoinsEngine's successor, nulli0n/ExcellentEconomy):
 *   a Bukkit service {@code su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI}
 *   with {@code getCurrency(String)}, {@code getBalance(Player,String)},
 *   {@code deposit(Player,String,double)} and {@code withdraw(Player,String,double)}.
 *   Its synchronous calls take an online {@link Player}; offline players are
 *   refused rather than routed through the async API.</li>
 *   <li><b>CoinsEngine 2.x</b> (FreshSMP/CoinsEngine-folia mirror): static
 *   {@code su.nightexpress.coinsengine.api.CoinsEngineAPI} with
 *   {@code getCurrency(String)}, {@code getBalance(UUID,Currency)},
 *   {@code addBalance(UUID,Currency,double)} and {@code removeBalance(UUID,Currency,double)}.</li>
 * </ul>
 */
public final class CoinsEngineCurrency extends AbstractCurrency {

    public static final String PREFIX = "coinsengine:";

    private static final String MODERN_API = "su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI";
    private static final String MODERN_CURRENCY = "su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency";
    private static final String LEGACY_API = "su.nightexpress.coinsengine.api.CoinsEngineAPI";
    private static final String LEGACY_CURRENCY = "su.nightexpress.coinsengine.api.currency.Currency";

    private final String currencyId;

    private volatile Backend backend;

    private interface Backend {
        boolean ready();
        Object currency();
        Class<?> currencyType();
        double balance(OfflinePlayer player);
        boolean add(OfflinePlayer player, double amount);
        boolean remove(OfflinePlayer player, double amount);
    }

    public CoinsEngineCurrency(String currencyId, CurrencyFormats formats) {
        super(PREFIX + currencyId.toLowerCase(java.util.Locale.ROOT), currencyId,
                CurrencyFormat.of("", " " + currencyId, 2), formats);
        this.currencyId = currencyId;
    }

    public String currencyId() {
        return currencyId;
    }

    private Backend backend() {
        Backend current = backend;
        if (current != null && current.ready()) return current;
        Backend built = null;
        Plugin modern = Reflection.enabledPlugin("ExcellentEconomy");
        if (modern != null) built = modern(modern);
        if (built == null) {
            Plugin legacy = Reflection.enabledPlugin("CoinsEngine");
            if (legacy != null) built = legacy(legacy);
        }
        backend = built;
        return built != null && built.ready() ? built : null;
    }

    private Backend modern(Plugin plugin) {
        try {
            Class<?> type = Reflection.load(plugin, MODERN_API);
            Object service = Bukkit.getServicesManager().load(type);
            if (service == null) return null;
            Method getCurrency = Reflection.method(type, "getCurrency", String.class);
            Method getBalance = Reflection.method(type, "getBalance", Player.class, String.class);
            Method deposit = Reflection.method(type, "deposit", Player.class, String.class, double.class);
            Method withdraw = Reflection.method(type, "withdraw", Player.class, String.class, double.class);
            Method canOperate = Reflection.optional(type, "canPerformOperations");
            Class<?> currencyType = Reflection.load(plugin, MODERN_CURRENCY);
            return new Backend() {
                public boolean ready() {
                    if (!plugin.isEnabled()) return false;
                    return canOperate == null || Reflection.asBoolean(Reflection.invoke(canOperate, service));
                }
                public Object currency() { return Reflection.invoke(getCurrency, service, currencyId); }
                public Class<?> currencyType() { return currencyType; }
                public double balance(OfflinePlayer player) {
                    Player online = player.getPlayer();
                    if (online == null) return 0.0;
                    return Reflection.asDouble(Reflection.invoke(getBalance, service, online, currencyId));
                }
                public boolean add(OfflinePlayer player, double amount) {
                    Player online = player.getPlayer();
                    return online != null && Reflection.asBoolean(Reflection.invoke(deposit, service, online, currencyId, amount));
                }
                public boolean remove(OfflinePlayer player, double amount) {
                    Player online = player.getPlayer();
                    return online != null && Reflection.asBoolean(Reflection.invoke(withdraw, service, online, currencyId, amount));
                }
            };
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return null;
        }
    }

    private Backend legacy(Plugin plugin) {
        try {
            Class<?> api = Reflection.load(plugin, LEGACY_API);
            Class<?> currencyType = Reflection.load(plugin, LEGACY_CURRENCY);
            Method isLoaded = Reflection.optional(api, "isLoaded");
            Method getCurrency = Reflection.method(api, "getCurrency", String.class);
            Method getBalance = Reflection.method(api, "getBalance", UUID.class, currencyType);
            Method addBalance = Reflection.method(api, "addBalance", UUID.class, currencyType, double.class);
            Method removeBalance = Reflection.method(api, "removeBalance", UUID.class, currencyType, double.class);
            return new Backend() {
                public boolean ready() {
                    if (!plugin.isEnabled()) return false;
                    return isLoaded == null || Reflection.asBoolean(Reflection.invoke(isLoaded, null));
                }
                public Object currency() { return Reflection.invoke(getCurrency, null, currencyId); }
                public Class<?> currencyType() { return currencyType; }
                public double balance(OfflinePlayer player) {
                    Object currency = currency();
                    if (currency == null) return 0.0;
                    return Reflection.asDouble(Reflection.invoke(getBalance, null, player.getUniqueId(), currency));
                }
                public boolean add(OfflinePlayer player, double amount) {
                    Object currency = currency();
                    if (currency == null) return false;
                    Object result = Reflection.invoke(addBalance, null, player.getUniqueId(), currency, amount);
                    return Reflection.asBoolean(result);
                }
                public boolean remove(OfflinePlayer player, double amount) {
                    Object currency = currency();
                    if (currency == null) return false;
                    return Reflection.asBoolean(Reflection.invoke(removeBalance, null, player.getUniqueId(), currency, amount));
                }
            };
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return null;
        }
    }

    @Override
    public boolean available() {
        try {
            Backend current = backend();
            return current != null && current.currency() != null;
        } catch (RuntimeException | LinkageError error) {
            return false;
        }
    }

    @Override
    public String unavailableReason() {
        if (Reflection.enabledPlugin("ExcellentEconomy", "CoinsEngine") == null) {
            return "CoinsEngine/ExcellentEconomy is not installed or not enabled";
        }
        return available() ? "" : "CoinsEngine currency '" + currencyId + "' does not exist or the API is not ready";
    }

    @Override
    public String displayName() {
        String configured = super.displayName();
        if (!configured.equals(currencyId)) return configured;
        Object name = currencyProperty("getName");
        return name instanceof String text && !text.isBlank() ? stripColor(text) : currencyId;
    }

    @Override
    public boolean wholeUnits() {
        Object decimal = currencyProperty("isDecimal");
        return decimal instanceof Boolean flag && !flag;
    }

    private Object currencyProperty(String method) {
        try {
            Backend current = backend();
            if (current == null) return null;
            Object currency = current.currency();
            if (currency == null) return null;
            Method getter = Reflection.optional(current.currencyType(), method);
            return getter == null ? null : Reflection.invoke(getter, currency);
        } catch (RuntimeException | LinkageError error) {
            return null;
        }
    }

    private static String stripColor(String text) {
        return text.replaceAll("(?i)[&§][0-9a-fk-orx]|<[^>]+>", "");
    }

    @Override
    public double balance(OfflinePlayer player) {
        if (player == null) return 0.0;
        try {
            Backend current = backend();
            return current == null ? 0.0 : current.balance(player);
        } catch (RuntimeException | LinkageError error) {
            return 0.0;
        }
    }

    @Override
    protected Result take(OfflinePlayer player, double amount) {
        Backend current = backend();
        if (current.balance(player) + 1e-9 < amount) return Result.fail("not enough " + displayName());
        return current.remove(player, amount) ? Result.ok(amount) : Result.fail("CoinsEngine rejected the withdrawal");
    }

    @Override
    protected Result give(OfflinePlayer player, double amount) {
        return backend().add(player, amount) ? Result.ok(amount) : Result.fail("CoinsEngine rejected the deposit");
    }
}
