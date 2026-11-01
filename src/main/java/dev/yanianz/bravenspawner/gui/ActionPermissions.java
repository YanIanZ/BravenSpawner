package dev.yanianz.bravenspawner.gui;

import org.bukkit.permissions.Permissible;

/**
 * Per-action permission nodes for spawner menus (PM-01).
 *
 * <p>A node only narrows what an already authorized player may do; it never grants
 * access to a spawner the player cannot otherwise use. Every node defaults to
 * {@code true} in {@code plugin.yml}, so servers that never configure them keep the
 * previous behaviour. Administrators are not exempt: an explicit negation applies
 * to everyone, matching how {@code bravenspawner.item.extract} already works.
 */
public enum ActionPermissions {
    SELL("bravenspawner.action.sell"),
    COLLECT_XP("bravenspawner.action.xp"),
    UPGRADE("bravenspawner.action.upgrade"),
    BUY_STACK("bravenspawner.action.buy");

    private final String node;

    ActionPermissions(String node) {
        this.node = node;
    }

    public String node() { return node; }

    public boolean allows(Permissible subject) {
        return subject != null && subject.hasPermission(node);
    }

    /** The node that gates a menu action, or null when the action is not gated here. */
    public static ActionPermissions of(GuiAction action) {
        if (action == null) return null;
        return switch (action) {
            case SELL_ALL -> SELL;
            case COLLECT_XP -> COLLECT_XP;
            case UPGRADE -> UPGRADE;
            case BUY_STACK_MENU, BUY_ONE, BUY_64, BUY_MAX -> BUY_STACK;
            default -> null;
        };
    }
}
