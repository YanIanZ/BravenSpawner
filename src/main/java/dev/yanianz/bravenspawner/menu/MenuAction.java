package dev.yanianz.bravenspawner.menu;

import org.bukkit.entity.Player;

/**
 * Callback invoked for one layout symbol of one menu.
 *
 * <p>The holder is passed alongside the player so an action can read the exact
 * placeholders the view was built with without closing over them; game logic
 * still lives with the caller, never in the menu engine.
 */
@FunctionalInterface
public interface MenuAction {

    void run(Player player, MenuHolder holder);
}
