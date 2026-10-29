package dev.yanianz.bravenspawner.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Routing for menu inventories: cancel every mutation, then dispatch the action
 * bound to the clicked layout symbol.
 *
 * <p>The engine deliberately owns no game logic. A click is resolved to the
 * symbol under the cursor in the layout that built the view, and only then is the
 * caller's callback invoked. Because the definition and the action map are both
 * captured by the holder, simultaneous viewers and definition reloads cannot
 * change the buttons of an already open window.
 *
 * <p>{@link #register} additionally binds an action to a menu name and symbol
 * for callers that prefer one long-lived registration over passing a map to
 * every open; a holder that was opened with its own actions always wins.
 */
public final class MenuListener implements Listener {

    private final MenuService service;
    private final Map<String, Map<Character, MenuAction>> registered = new ConcurrentHashMap<>();

    MenuListener(MenuService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /** Binds a fallback action to one layout symbol of one menu. */
    public void register(String menu, char symbol, MenuAction action) {
        if (menu == null || action == null) return;
        registered.computeIfAbsent(menu.toLowerCase(Locale.ROOT), key -> new ConcurrentHashMap<>())
                .put(symbol, action);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) return;

        // Cancel before any identity check: even a stale window, a shift-click or a
        // click in the player's own rows must not move an item through the menu.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || clicked != top) return;

        MenuHolder current = service.session(player);
        if (current == null || current != holder || !holder.isCurrent(top)) return;

        int slot = event.getSlot();
        Consumer<Player> action = holder.action(slot);
        if (action != null) {
            action.accept(player);
            return;
        }
        MenuAction fallback = fallback(holder.menu(), symbol(holder, slot));
        if (fallback != null) fallback.run(player, holder);
    }

    private static char symbol(MenuHolder holder, int slot) {
        MenuDefinition definition = holder.definition();
        return definition == null ? 0 : definition.symbol(holder.layout(), slot);
    }

    private MenuAction fallback(String menu, char symbol) {
        Map<Character, MenuAction> bySymbol = registered.get(menu.toLowerCase(Locale.ROOT));
        return bySymbol == null ? null : bySymbol.get(symbol);
    }

    /**
     * Cancels drags that touch a menu.
     *
     * <p>A drag can deposit several items without producing a single click on the
     * top inventory, so the click guard alone would leave a write path open.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder)) return;
        event.setCancelled(true);
    }

    /**
     * Drops the player's session only when the window that closed is the current
     * one, so the close event of a replaced window cannot erase the new session.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) return;
        if (event.getPlayer() instanceof Player player) service.closed(player, holder);
    }

}
