package dev.yanianz.bravenspawner.menu;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable identity of one rendered menu view.
 *
 * <p>The generation is assigned by {@link MenuService} for every open, so a
 * holder left behind by a previous window is distinguishable from the current
 * one even when both describe the same menu. The placeholder map is copied so a
 * caller mutating its map after {@code open} cannot change what an already
 * rendered view claims to show.
 */
public final class MenuSession {

    private final String menu;
    private final String layout;
    private final Map<String, String> placeholders;
    private final long generation;

    public MenuSession(String menu, String layout, Map<String, String> placeholders, long generation) {
        this.menu = menu == null ? "" : menu;
        this.layout = layout == null ? "" : layout;
        Map<String, String> copy = new LinkedHashMap<>();
        if (placeholders != null) copy.putAll(placeholders);
        this.placeholders = Collections.unmodifiableMap(copy);
        this.generation = generation;
    }

    public String menu() {
        return menu;
    }

    public String layout() {
        return layout;
    }

    /** Unmodifiable placeholder values used to build the view. */
    public Map<String, String> placeholders() {
        return placeholders;
    }

    /** Monotonic open counter; the newest view always has the highest value. */
    public long generation() {
        return generation;
    }
}
