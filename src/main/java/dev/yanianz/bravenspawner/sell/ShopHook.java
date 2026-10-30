package dev.yanianz.bravenspawner.sell;

import dev.yanianz.bravenspawner.BravenSpawner;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads hooks.yml selection and reports plugin presence separately from API readiness.
 * Functional unit quotes are supplied by the selected commerce ShopBridge adapter;
 * settlement uses the sell currency. Detection alone does not establish pricing.
 */
public final class ShopHook {

    private static final String FILE_NAME = "hooks.yml";

    private static final String TEMPLATE = """
            # BravenSpawner hooks
            #
            # currency: default currency for every action that config.yml
            #   economy.currencies does not set. Vault, PlayerPoints, coinsengine:<id>,
            #   EXP_LEVELS, EXP_POINTS, ITEM. SuperMobCoins has no public API and is
            #   not supported (the action is refused, never free).
            currency: "Vault"
            #
            # shop: selected sell-price source. None uses local prices.
            #   Supported: ShopGUI+, EconomyShopGUI, ZShop, ExcellentShop,
            #              DonutWorth, EssentialsX, Nascraft
            #   "None" disables the integration.
            #
            # EssentialsX, ShopGUI+ and EconomyShopGUI have typed price adapters; ZShop,
            # ExcellentShop, Nascraft and DonutWorth are read through their public
            # API by reflection. Payouts use economy.currencies.sell; any API
            # failure falls back to the configured local sell-prices.
            shop: "None"
            """;

    private record Shop(String display, List<String> pluginNames) {}

    private static final Map<String, Shop> SUPPORTED = supported();

    private final BravenSpawner plugin;

    private String currency = "Vault";
    private String configuredShop = "None";
    private String detectedPlugin;

    public ShopHook(BravenSpawner plugin) {
        this.plugin = plugin;
    }

    /**
     * Creates the file on first boot and re-reads it afterwards.
     *
     * <p>The file is written from code because {@code hooks.yml} is not a bundled
     * resource; a plain write keeps the shipped jar unchanged while still giving
     * admins a documented file to edit.
     */
    public void load() {
        File file = ensureFile();
        if (file == null) return;

        FileConfiguration config;
        try {
            config = YamlConfiguration.loadConfiguration(file);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("hooks.yml is unreadable (" + e.getMessage() + "); using currency=Vault, shop=None.");
            return;
        }

        currency = config.getString("currency", "Vault");
        configuredShop = config.getString("shop", "None");
        detectedPlugin = null;
        report();
    }

    /** Currency name exactly as configured; the registry uses it as the default selection. */
    public String currency() {
        return currency;
    }

    /** Shop name exactly as configured, for logging and admin diagnostics. */
    public String configuredShop() {
        return configuredShop;
    }

    /** Bukkit name of the detected shop plugin, or null when none matched. */
    public String detectedPlugin() {
        return detectedPlugin;
    }

    /** True when the configured shop plugin is actually installed. */
    public boolean available() {
        return detectedPlugin != null;
    }

    private File ensureFile() {
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("could not create " + folder + "; shop hooks stay disabled.");
            return null;
        }
        File file = new File(folder, FILE_NAME);
        if (file.exists()) return file;

        try {
            Files.writeString(file.toPath(), TEMPLATE, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException ignored) {
            // Another thread won the race; the file is there either way.
        } catch (IOException e) {
            plugin.getLogger().warning("could not write hooks.yml: " + e.getMessage());
            return file;
        }
        return file;
    }

    private void report() {
        if (!currency.equalsIgnoreCase("Vault")) {
            plugin.getLogger().info("hooks.yml currency '" + currency
                    + "' is the default for actions not set in config.yml economy.currencies.");
        }

        String key = normalize(configuredShop);
        if (key.isEmpty() || key.equals("NONE") || key.equals("DISABLED")) {
            plugin.getLogger().info("hooks.yml shop integration is disabled (shop: " + configuredShop + ").");
            return;
        }

        Shop hook = SUPPORTED.get(key);
        if (hook == null) {
            plugin.getLogger().warning("hooks.yml shop '" + configuredShop
                    + "' is not supported; known shops: " + knownShops() + ".");
            return;
        }

        for (String candidate : hook.pluginNames()) {
            var present = Bukkit.getPluginManager().getPlugin(candidate);
            if (present != null && present.isEnabled()) {
                detectedPlugin = present.getName();
                plugin.getLogger().info("Shop hook: '" + hook.display() + "' detected as plugin '"
                        + detectedPlugin + "'; price capability is checked by the selected adapter.");
                return;
            }
        }
        plugin.getLogger().info("Shop hook: '" + hook.display() + "' configured but not installed (looked for "
                + String.join(", ", hook.pluginNames()) + "); local sell-prices are used.");
    }

    private static String knownShops() {
        StringBuilder out = new StringBuilder();
        for (Shop hook : SUPPORTED.values()) {
            if (!out.isEmpty()) out.append(", ");
            out.append(hook.display());
        }
        return out.toString();
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        return raw.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    }

    private static Map<String, Shop> supported() {
        Map<String, Shop> map = new LinkedHashMap<>();
        add(map, new Shop("ShopGUI+", List.of("ShopGUIPlus", "ShopGUI+")), "SHOPGUI+", "SHOPGUIPLUS");
        add(map, new Shop("EconomyShopGUI", List.of("EconomyShopGUI", "EconomyShopGUI-Premium")),
                "ECONOMYSHOPGUI", "ECONOMYSHOPGUI-PREMIUM", "ECONOMYSHOPGUIPREMIUM");
        add(map, new Shop("ZShop", List.of("zShop", "ZShop")), "ZSHOP");
        add(map, new Shop("ExcellentShop", List.of("ExcellentShop")), "EXCELLENTSHOP");
        add(map, new Shop("DonutWorth", List.of("DonutWorth")), "DONUTWORTH");
        add(map, new Shop("EssentialsX", List.of("Essentials", "EssentialsX")), "ESSENTIALSX", "ESSENTIALS");
        add(map, new Shop("Nascraft", List.of("Nascraft")), "NASCRAFT");
        return java.util.Collections.unmodifiableMap(map);
    }

    private static void add(Map<String, Shop> map, Shop hook, String... aliases) {
        for (String alias : aliases) map.put(alias, hook);
    }
}
