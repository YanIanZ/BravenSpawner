package dev.yanianz.bravenspawner.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/**
 * Callback bound to one layout symbol of an open view.
 *
 * <p>The click type is passed through so one button can carry two actions, as
 * the shipped menus do (left-click opens storage, right-click sells it).
 */
@FunctionalInterface
public interface MenuClick {

    void click(Player player, ClickType type);
}
