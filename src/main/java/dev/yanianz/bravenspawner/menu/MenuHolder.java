package dev.yanianz.bravenspawner.menu;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import java.util.Collections;
import java.util.LinkedHashMap;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;
import java.util.Objects;

/**
 * Typed identity of one open menu, carried by the inventory itself.
 *
 * <p>Routing a click by title would break as soon as a title contains a
 * placeholder, and routing by player alone would accept clicks from a window
 * that has already been replaced. The holder therefore owns the session and the
 * exact {@link Inventory} instance that was built for it; {@link #isCurrent}
 * is an identity check, not a heuristic.
 */
public final class MenuHolder implements InventoryHolder {

    private final MenuSession session;
    private Inventory inventory;
    private final MenuDefinition definition;
    private final Map<Character, MenuClick> actions;

    public MenuHolder(MenuSession session) {
        this(session, null, Map.of());
    }

    public MenuHolder(MenuSession session, MenuDefinition definition, Map<Character, MenuClick> actions) {
        this.session = Objects.requireNonNull(session, "session");
        this.definition = definition;
        this.actions = actions == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(actions));
    }

    MenuDefinition definition() { return definition; }

    /**
     * Callback for the symbol under {@code slot} in this view, or null.
     *
     * <p>Actions are captured per holder, so a later open or a definition reload
     * cannot reinterpret the buttons of a window that is already on screen.
     */
    MenuClick action(int slot) {
        if (definition == null) return null;
        return actions.get(definition.symbol(layout(), slot));
    }

    void click(Player player, int slot, org.bukkit.event.inventory.ClickType type) {
        MenuClick action = action(slot);
        if (action != null) action.click(player, type);
    }

    /**
     * Binds the holder to its inventory.
     *
     * <p>Package-private because only {@link MenuRenderer} may create the view a
     * holder represents; a caller cannot point an existing session at another
     * window and inherit its clicks.
     */
    void attach(Inventory inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** True only for the exact inventory instance this session produced. */
    public boolean isCurrent(Inventory other) {
        return other != null && other == inventory;
    }

    public String menu() {
        return session.menu();
    }

    public String layout() {
        return session.layout();
    }

    public Map<String, String> placeholders() {
        return session.placeholders();
    }

    public long generation() {
        return session.generation();
    }

    public MenuSession session() {
        return session;
    }
}
