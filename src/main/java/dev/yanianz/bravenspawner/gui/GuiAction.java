package dev.yanianz.bravenspawner.gui;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Every {@code nbt:} action a menu button can carry, with the aliases used by the
 * shipped and reference menus.
 *
 * <p>The GUI dispatches with an exhaustive switch over this enum, so adding an
 * action without a handler is a compile error, and a test asserts that every
 * {@code nbt:} value in the bundled menus parses.
 */
public enum GuiAction {
    STORAGE("storage"),
    COLLECT_XP("xp", "collect_xp"),
    TAKE_ALL("takeall", "take_all", "collect_items"),
    SELL_ALL("sellall", "sell_all", "bedrock"),
    COLLECT_ALL("collect_all", "collectall"),
    TAKE_ONE("takeone", "take_one"),
    TAKE_STACK("takestack", "take_stack"),
    DROP_ALL("dropall", "drop_all"),
    PREVIOUS("previous"),
    NEXT("next"),
    BACK("goback", "go_back", "back"),
    CLOSE("close"),
    UPGRADE("upgrade"),
    TRUSTED("trusted"),
    BUY_STACK_MENU("buy_stack", "buystack", "information"),
    LOG("log"),
    LOG_TARGET("target"),
    LOG_TYPE("logtype"),
    LOG_LIMIT("limit"),
    LOG_TIME("time"),
    BUY_ONE("buy_1"),
    BUY_64("buy_64"),
    BUY_MAX("buy_max"),
    BOOSTERS("boosters");

    private static final Map<String, GuiAction> BY_NAME = new HashMap<>();

    static {
        for (GuiAction action : values()) {
            for (String alias : action.aliases) BY_NAME.put(alias, action);
        }
    }

    private final String[] aliases;

    GuiAction(String... aliases) {
        this.aliases = aliases;
    }

    /** The action for an {@code nbt:} value, or null when it is blank or unknown. */
    public static GuiAction parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return BY_NAME.get(raw.trim().toLowerCase(Locale.ROOT));
    }
}
