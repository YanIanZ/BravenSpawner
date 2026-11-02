package dev.yanianz.bravenspawner.item;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and recognises drops that come from custom-item plugins.
 *
 * <p>Each referenced item is built once and cached as a template, so every unit a
 * spawner produces is identical and stacks into one storage entry (MMOItems would
 * otherwise roll fresh random stats per call). The cache is dropped on reload.
 * An item the provider does not know yet is not cached: ItemsAdder, Oraxen and
 * Nexo finish loading after server start, and such a drop is simply skipped until
 * the provider has it. Missing plugins, incompatible versions and unknown ids are
 * logged once and produce nothing (fail closed).
 */
public final class CustomItems {

    private static final List<String> PROVIDERS = List.of(CustomItemRef.MMOITEMS, CustomItemRef.ECOITEMS,
            CustomItemRef.ITEMSADDER, CustomItemRef.ORAXEN, CustomItemRef.NEXO, CustomItemRef.MYTHICMOBS);

    private final Plugin plugin;
    private final Map<String, Binding> bindings = new ConcurrentHashMap<>();
    private final Map<CustomItemRef, ItemStack> templates = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** A probed adapter, or the reason it is unusable. */
    private record Binding(ItemPluginAdapter adapter, String failure) {}

    public CustomItems(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * One unit of {@code ref}, or {@code null} when it cannot be made right now.
     *
     * @param product the product id, used only in the log line
     */
    public ItemStack create(CustomItemRef ref, String product) {
        ItemStack template = templates.get(ref);
        if (template == null) {
            ItemPluginAdapter adapter = adapter(ref.provider(), product);
            if (adapter == null) return null;
            ItemStack made;
            try {
                made = adapter.create(ref);
            } catch (RuntimeException | LinkageError e) {
                warnOnce("create:" + ref, "product " + product + ": " + name(ref) + " failed to create '"
                        + ref + "' (" + cause(e) + "); drop skipped.");
                return null;
            }
            if (made == null || made.getType().isAir()) {
                warnOnce("missing:" + ref, "product " + product + ": " + name(ref) + " has no item '"
                        + ref.key() + "' (yet); drop skipped until it exists.");
                return null;
            }
            made = made.clone();
            made.setAmount(1);
            ItemStack previous = templates.putIfAbsent(ref, made);
            template = previous == null ? made : previous;
        }
        return template.clone();
    }

    /** True when {@code stack} is the provider's item {@code ref}; false when unknown or unavailable. */
    public boolean matches(CustomItemRef ref, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        ItemPluginAdapter adapter = adapter(ref.provider(), null);
        if (adapter == null) return false;
        String id;
        try {
            id = adapter.identify(stack);
        } catch (RuntimeException | LinkageError e) {
            warnOnce("identify:" + ref.provider(), name(ref) + " could not identify an item ("
                    + cause(e) + "); its drops are not priced.");
            return false;
        }
        if (id == null) return false;
        if (id.equals(ref.key())) return true;
        // ItemsAdder ids are namespace:id; a reference may leave the namespace out.
        return ref.provider().equals(CustomItemRef.ITEMSADDER) && ref.key().indexOf(':') < 0
                && id.endsWith(":" + ref.key());
    }

    /** Clears templates, bindings and the log-once memory; called on reload. */
    public void reload() {
        templates.clear();
        bindings.clear();
        warned.clear();
    }

    /** One line per provider: plugin name and whether it is usable, for {@code /bspawner doctor}. */
    public List<String> status() {
        List<String> lines = new ArrayList<>();
        for (String provider : PROVIDERS) {
            String pluginName = ItemPluginAdapter.pluginName(provider);
            Plugin owner = plugin.getServer().getPluginManager().getPlugin(pluginName);
            String state;
            if (owner == null) state = "not installed";
            else if (!owner.isEnabled()) state = "installed, not enabled";
            else {
                Binding binding = bind(provider, owner);
                state = binding.adapter() != null ? "ready (" + provider + ":...)" : "incompatible: " + binding.failure();
            }
            lines.add(pluginName + ": " + state);
        }
        return lines;
    }

    private ItemPluginAdapter adapter(String provider, String product) {
        String pluginName = ItemPluginAdapter.pluginName(provider);
        Plugin owner = plugin.getServer().getPluginManager().getPlugin(pluginName);
        if (owner == null || !owner.isEnabled()) {
            if (product != null) {
                warnOnce("absent:" + provider, "product " + product + " drops " + provider + " items but "
                        + pluginName + " is not enabled; those drops are skipped.");
            }
            return null;
        }
        Binding binding = bind(provider, owner);
        if (binding.adapter() == null) {
            warnOnce("bind:" + provider, pluginName + " " + owner.getPluginMeta().getVersion()
                    + " is not compatible (" + binding.failure() + "); its drops are skipped.");
        }
        return binding.adapter();
    }

    private Binding bind(String provider, Plugin owner) {
        // Only reached with the plugin enabled, so loading the adapter class is safe.
        return bindings.computeIfAbsent(provider, key -> {
            try {
                ItemPluginAdapter adapter = ItemPluginAdapter.of(key);
                adapter.probe();
                return new Binding(adapter, null);
            } catch (RuntimeException | LinkageError e) {
                return new Binding(null, cause(e));
            }
        });
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) plugin.getLogger().warning(message);
    }

    private static String name(CustomItemRef ref) {
        return ItemPluginAdapter.pluginName(ref.provider());
    }

    private static String cause(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
