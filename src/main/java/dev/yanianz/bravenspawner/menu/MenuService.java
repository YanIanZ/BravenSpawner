package dev.yanianz.bravenspawner.menu;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Facade the rest of the plugin uses to open YAML menus.
 *
 * <p>One constructor wires everything — loader, renderer, listener — so the
 * bootstrap only needs {@code new MenuService(this)} and never touches the
 * engine's internals. Every open returns the {@link MenuHolder} it produced, so
 * a caller that needs to fill dynamic slots (storage contents, puzzle panes) can
 * still use the exact inventory the engine created.
 *
 * <p>Sessions are kept per player and per holder generation. A click is honoured
 * only when the holder that owns it is still the player's current session and
 * still points at the clicked inventory, which makes a rapid close/open unable
 * to route a stale event into the new window.
 */
public final class MenuService {

    private final JavaPlugin plugin;
    private final MenuLoader loader;
    private final MenuRenderer renderer;
    private final MenuListener listener;
    private final AtomicLong generations = new AtomicLong();
    private final Map<UUID, MenuHolder> sessions = new ConcurrentHashMap<>();

    public MenuService(JavaPlugin plugin) {
        this(plugin, new MenuLoader(plugin), new MenuRenderer());
    }

    MenuService(JavaPlugin plugin, MenuLoader loader, MenuRenderer renderer) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.loader = Objects.requireNonNull(loader, "loader");
        int loaded = loader.load();
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.listener = new MenuListener(this);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        plugin.getLogger().info("Loaded " + loaded + " GUI menu(s) from " + loader.directory());
    }

    /**
     * Opens a menu with its default layout.
     *
     * @param actions callbacks per layout symbol; may be null for a display-only menu
     * @return the open holder, or null when the menu is unknown or the server refused the window
     */
    public MenuHolder open(Player player, String menu,
                           Map<String, String> placeholders,
                           Map<Character, MenuClick> actions) {
        return open(player, menu, null, placeholders, actions);
    }

    /**
     * Opens a menu with a named layout.
     *
     * <p>An unknown layout name falls back to the definition's default, because
     * the same menu is opened for regular and bedrock players and the caller
     * should not have to duplicate the fallback.
     */
    public MenuHolder open(Player player, String menu, String layout,
                           Map<String, String> placeholders,
                           Map<Character, MenuClick> actions) {
        Objects.requireNonNull(player, "player");
        if (!player.isOnline()) return null;

        MenuDefinition definition = loader.get(menu);
        if (definition == null) {
            plugin.getLogger().warning("GUI menu '" + menu + "' is not loaded; open ignored.");
            return null;
        }
        String chosen = layout == null || layout.isBlank() || !definition.hasLayout(layout)
                ? definition.defaultLayout()
                : layout;

        MenuSession session = new MenuSession(definition.name(), chosen,
                placeholders, generations.incrementAndGet());
        MenuHolder holder = new MenuHolder(session, definition, actions);
        Inventory inventory;
        if (plugin instanceof dev.yanianz.bravenspawner.BravenSpawner spawners
                && spawners.settings() != null && spawners.settings().placeholdersInGui
                && spawners.placeholders() != null) {
            inventory = renderer.render(definition, chosen, placeholders, holder,
                    text -> spawners.placeholders().apply(player, text));
        } else {
            inventory = renderer.render(definition, chosen, placeholders, holder);
        }

        player.openInventory(inventory);
        // Paper opens synchronously; a different top inventory means another plugin
        // (or a permission gate) refused this one, so no session may be recorded.
        if (player.getOpenInventory().getTopInventory() != inventory) {
            plugin.getLogger().warning("GUI menu '" + definition.name() + "' was not accepted for "
                    + player.getName() + "; session not recorded.");
            return null;
        }
        sessions.put(player.getUniqueId(), holder);
        return holder;
    }

    /** Loaded definition by name, or null when the file is absent or malformed. */
    public MenuDefinition definition(String menu) {
        return loader.get(menu);
    }

    /** Names of every menu currently loaded. */
    public Set<String> menus() {
        return loader.names();
    }

    /** The player's current menu session, or null when they have none open. */
    public MenuHolder session(Player player) {
        return player == null ? null : sessions.get(player.getUniqueId());
    }

    /** Re-reads the GUI directory; open windows keep the definition they rendered. */
    public int reload() {
        int loaded = loader.load();
        plugin.getLogger().info("Reloaded " + loaded + " GUI menu(s).");
        return loaded;
    }

    /**
     * Removes a session on close.
     *
     * <p>Package-private: only the listener observes closes, and the conditional
     * remove protects a session that was already replaced by a newer open.
     */
    void closed(Player player, MenuHolder holder) {
        if (player == null || holder == null) return;
        sessions.remove(player.getUniqueId(), holder);
    }
}
