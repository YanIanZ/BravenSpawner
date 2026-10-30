package dev.yanianz.bravenspawner.restrictions;

import org.bukkit.configuration.ConfigurationSection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable policy: locked menus and sell-only inventory maintenance are separate switches. */
public record RestrictionRules(boolean lockMode, boolean allowTakeItems, Set<String> restricted, String message) {
    public RestrictionRules { restricted = Set.copyOf(restricted); }
    public static RestrictionRules from(ConfigurationSection config) {
        return new RestrictionRules(config.getBoolean("settings.lockmode", false),
                config.getBoolean("settings.allow-take-items", false),
                config.getStringList("restricted").stream().map(v -> v.toUpperCase(Locale.ROOT)).collect(Collectors.toSet()),
                config.getString("messages.restricted", "&cAccess to the %spawner_type% spawner is restricted."));
    }
    public boolean canOpen(String product, boolean bypass) { return bypass || !lockMode || !restricted(product); }
    public boolean canTakeItems(String product, boolean bypass) { return bypass || allowTakeItems || !restricted(product); }
    private boolean restricted(String product) { return product != null && restricted.contains(product.toUpperCase(Locale.ROOT)); }
}
