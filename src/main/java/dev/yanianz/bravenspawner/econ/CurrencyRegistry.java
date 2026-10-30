package dev.yanianz.bravenspawner.econ;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.econ.currency.CoinsEngineCurrency;
import dev.yanianz.bravenspawner.econ.currency.ExpCurrency;
import dev.yanianz.bravenspawner.econ.currency.ItemCurrency;
import dev.yanianz.bravenspawner.econ.currency.PlayerPointsCurrency;
import dev.yanianz.bravenspawner.econ.currency.UnavailableCurrency;
import dev.yanianz.bravenspawner.econ.currency.VaultCurrency;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServiceRegisterEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Every currency this server can use, and which one each action pays with.
 *
 * <p>Lookup is case-insensitive ({@code vault}, {@code Vault}, {@code VAULT}).
 * Prefixed ids such as {@code coinsengine:gems} are created on first lookup.
 * Provider plugins may enable after this plugin, so availability is never cached:
 * {@link #forAction} re-checks every time, and plugin enable/disable events
 * reset the "logged once" state and announce selections that became usable.
 *
 * <p>{@link #forAction} never returns null and never returns a free currency: an
 * unknown id or {@code economy.enabled: false} yields an
 * {@link UnavailableCurrency} whose transactions all fail.
 */
public final class CurrencyRegistry implements Listener {

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("ECONOMY", VaultCurrency.ID),
            Map.entry("MONEY", VaultCurrency.ID),
            Map.entry("POINTS", PlayerPointsCurrency.ID),
            Map.entry("PLAYER_POINTS", PlayerPointsCurrency.ID),
            Map.entry("EXP", ExpCurrency.POINTS),
            Map.entry("XP", ExpCurrency.POINTS),
            Map.entry("EXPERIENCE", ExpCurrency.POINTS),
            Map.entry("LEVELS", ExpCurrency.LEVELS),
            Map.entry("XP_LEVELS", ExpCurrency.LEVELS),
            Map.entry("ITEMS", ItemCurrency.ID));

    private static final Map<String, String> PREFIX_ALIASES = Map.of(
            "EXCELLENTECONOMY", "COINSENGINE");

    /** Known ids that cannot be supported, with the reason shown to admins. */
    private static final Map<String, String> UNSUPPORTED = Map.of(
            "SUPERMOBCOINS", "SuperMobCoins publishes no public API to integrate against; use Vault, PlayerPoints or CoinsEngine");

    private final Logger logger;
    private final CurrencyFormats formats;
    private final Supplier<String> defaultSource;
    private volatile String defaultId;
    private final Map<String, Currency> builtins = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Function<String, Currency>> prefixes = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Currency> dynamic = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public CurrencyRegistry(Logger logger, CurrencyFormats formats, Supplier<String> defaultId) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.formats = Objects.requireNonNull(formats, "formats");
        this.defaultSource = defaultId == null ? () -> VaultCurrency.ID : defaultId;
        this.defaultId = readDefault();
    }

    /** The production registry with every built-in provider. */
    public static CurrencyRegistry create(BravenSpawner plugin) {
        CurrencyFormats formats = CurrencyFormats.of(plugin);
        CurrencyRegistry registry = new CurrencyRegistry(plugin.getLogger(), formats,
                () -> hooksCurrency(plugin.getDataFolder()));
        registry.register(new VaultCurrency(plugin::economy, registry::enabled, formats));
        registry.register(new PlayerPointsCurrency(formats));
        registry.register(ExpCurrency.levels(formats));
        registry.register(ExpCurrency.points(formats));
        registry.register(new ItemCurrency(formats));
        registry.registerPrefix("coinsengine", id -> new CoinsEngineCurrency(id, formats));
        return registry;
    }

    /**
     * Currency for {@code action} even when no registry was installed (tests,
     * early boot): falls back to the plain Vault bridge so behaviour matches
     * the pre-registry plugin.
     */
    public static Currency select(BravenSpawner plugin, CurrencyAction action) {
        CurrencyRegistry registry = plugin.currencies();
        if (registry != null) return registry.forAction(action);
        return new VaultCurrency(plugin::economy, () -> false, CurrencyFormats.of(plugin));
    }

    /** {@code hooks.yml: currency}, the reference plugin's single-currency switch; VAULT when absent. */
    static String hooksCurrency(File folder) {
        try {
            File file = new File(folder, "hooks.yml");
            if (!file.isFile()) return VaultCurrency.ID;
            String value = YamlConfiguration.loadConfiguration(file).getString("currency", VaultCurrency.ID);
            return value == null || value.isBlank() ? VaultCurrency.ID : value.trim();
        } catch (RuntimeException unreadable) {
            return VaultCurrency.ID;
        }
    }

    public CurrencyRegistry register(Currency currency) {
        builtins.put(normalize(currency.id()), currency);
        return this;
    }

    /** Registers a factory for ids written as {@code prefix:<sub-id>}. */
    public CurrencyRegistry registerPrefix(String prefix, Function<String, Currency> factory) {
        prefixes.put(normalize(prefix), factory);
        return this;
    }

    /** Upper-case, spaces and dashes as underscores; used for every comparison. */
    public static String normalize(String id) {
        if (id == null) return "";
        return id.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    public Optional<Currency> lookup(String id) {
        String key = normalize(id);
        if (key.isEmpty()) return Optional.empty();
        int colon = key.indexOf(':');
        if (colon > 0) {
            String prefix = key.substring(0, colon);
            prefix = PREFIX_ALIASES.getOrDefault(prefix, prefix);
            String sub = id.trim().substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            Function<String, Currency> factory = prefixes.get(prefix);
            if (factory == null || sub.isEmpty()) return Optional.empty();
            String canonical = prefix + ":" + normalize(sub);
            return Optional.ofNullable(dynamic.computeIfAbsent(canonical, ignored -> factory.apply(sub)));
        }
        key = ALIASES.getOrDefault(key, key);
        return Optional.ofNullable(builtins.get(key));
    }

    /** {@code economy.enabled}; the master switch for every currency. */
    public boolean enabled() {
        ConfigurationSection economy = formats.economy();
        return economy != null && economy.getBoolean("enabled", false);
    }

    /** {@code economy.currencies.<action>}, else {@code hooks.yml currency}, else VAULT. */
    public String configuredId(CurrencyAction action) {
        ConfigurationSection economy = formats.economy();
        String fallback = defaultId;
        if (economy == null) return fallback;
        String value = economy.getString("currencies." + action.key(), null);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public Currency forAction(CurrencyAction action) {
        String id = configuredId(action);
        if (!enabled()) {
            return refuse(action, id, id, "economy.enabled is false in config.yml");
        }
        Optional<Currency> found = lookup(id);
        if (found.isEmpty()) {
            String note = UNSUPPORTED.get(normalize(id));
            return refuse(action, id, id, note != null ? note
                    : "unknown currency '" + id + "' (known: " + String.join(", ", knownIds()) + ")");
        }
        Currency currency = found.get();
        String key = action.key() + "|" + normalize(id);
        if (currency.available()) {
            warned.remove(key);
        } else if (warned.add(key)) {
            logger.warning("Currency " + currency.id() + " for '" + action.key() + "' is unavailable: "
                    + currency.unavailableReason() + ". That action is refused until it becomes available.");
        }
        return currency;
    }

    private Currency refuse(CurrencyAction action, String id, String name, String reason) {
        if (warned.add(action.key() + "|" + normalize(id))) {
            logger.warning("Currency '" + id + "' for '" + action.key() + "' cannot be used: " + reason
                    + ". That action is refused; nothing is ever given for free.");
        }
        return new UnavailableCurrency(normalize(id), name, reason, formats);
    }

    /** Built-ins in registration order followed by every prefixed currency looked up so far. */
    public List<Currency> known() {
        List<Currency> out;
        synchronized (builtins) {
            out = new ArrayList<>(builtins.values());
        }
        out.addAll(dynamic.values());
        return out;
    }

    public List<String> knownIds() {
        List<String> ids = new ArrayList<>();
        for (Currency currency : known()) ids.add(currency.id());
        synchronized (prefixes) {
            for (String prefix : prefixes.keySet()) ids.add(prefix.toLowerCase(Locale.ROOT) + ":<id>");
        }
        return ids;
    }

    /** Called after {@code /bspawner reload}: forget logged warnings so new settings are re-reported. */
    public void reload() {
        defaultId = readDefault();
        warned.clear();
    }

    private String readDefault() {
        try {
            String value = defaultSource.get();
            return value == null || value.isBlank() ? VaultCurrency.ID : value.trim();
        } catch (RuntimeException failure) {
            return VaultCurrency.ID;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        refresh();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        refresh();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServiceRegister(ServiceRegisterEvent event) {
        refresh();
    }

    /** Re-evaluates selections after a provider plugin appeared or vanished. */
    void refresh() {
        Set<String> before = Set.copyOf(warned);
        warned.clear();
        if (!enabled()) return;
        for (CurrencyAction action : CurrencyAction.values()) {
            String key = action.key() + "|" + normalize(configuredId(action));
            boolean wasMissing = before.contains(key);
            Currency currency = lookup(configuredId(action)).orElse(null);
            if (currency == null) {
                if (wasMissing) warned.add(key);
                continue;
            }
            if (currency.available()) {
                if (wasMissing) logger.info("Currency " + currency.id() + " for '" + action.key() + "' is now available.");
            } else if (wasMissing) {
                warned.add(key);
            }
        }
    }
}
