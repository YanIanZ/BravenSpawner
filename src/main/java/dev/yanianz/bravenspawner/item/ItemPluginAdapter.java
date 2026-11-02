package dev.yanianz.bravenspawner.item;

import org.bukkit.inventory.ItemStack;

/**
 * Direct bridge to one custom-item plugin's public API.
 *
 * <p>The APIs are compile-only dependencies. Each implementation lives in its own
 * class and is instantiated by {@link #of} only after {@link CustomItems} has
 * confirmed the plugin is installed and enabled, so the JVM never resolves a
 * plugin class that is absent. A different plugin version that removed a method
 * surfaces as a {@link LinkageError}, which {@link CustomItems} catches and turns
 * into a skipped drop.
 */
interface ItemPluginAdapter {

    /** Touches the API once so an incompatible version fails here, not mid-production. */
    void probe();

    /** A fresh item for {@code ref}, or {@code null} when the plugin does not know it. */
    ItemStack create(CustomItemRef ref);

    /** The plugin's id for {@code stack} in {@link CustomItemRef#key()} form, or {@code null}. */
    String identify(ItemStack stack);

    /** Plugin name as registered with Bukkit. */
    static String pluginName(String provider) {
        return switch (provider) {
            case CustomItemRef.MMOITEMS -> "MMOItems";
            case CustomItemRef.ECOITEMS -> "EcoItems";
            case CustomItemRef.ITEMSADDER -> "ItemsAdder";
            case CustomItemRef.ORAXEN -> "Oraxen";
            case CustomItemRef.NEXO -> "Nexo";
            case CustomItemRef.MYTHICMOBS -> "MythicMobs";
            default -> throw new IllegalArgumentException("unknown item provider " + provider);
        };
    }

    /** Creates the adapter. Call only when {@link #pluginName} is enabled. */
    static ItemPluginAdapter of(String provider) {
        return switch (provider) {
            case CustomItemRef.MMOITEMS -> new MmoItemsAdapter();
            case CustomItemRef.ECOITEMS -> new EcoItemsAdapter();
            case CustomItemRef.ITEMSADDER -> new ItemsAdderAdapter();
            case CustomItemRef.ORAXEN -> new OraxenAdapter();
            case CustomItemRef.NEXO -> new NexoAdapter();
            case CustomItemRef.MYTHICMOBS -> new MythicMobsAdapter();
            default -> throw new IllegalArgumentException("unknown item provider " + provider);
        };
    }
}
