package dev.yanianz.bravenspawner.platform;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves PlaceholderAPI placeholders when the plugin is installed.
 *
 * <p>Presence is resolved once because a running server cannot gain or lose a
 * plugin; the per-call cost is then a boolean and, at worst, one scan of the
 * string. Text with no '%' is returned untouched — PlaceholderAPI would copy it
 * anyway, and GUI lore is mostly static.
 *
 * <p>Every method is null-safe: menu code renders lines for a viewer that may have
 * disconnected, and one null player must not abort a whole render pass.
 */
public final class Placeholders {

    private final boolean available;

    public Placeholders() {
        Plugin placeholderApi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        this.available = placeholderApi != null && placeholderApi.isEnabled();
    }

    public boolean available() { return available; }

    /** Returns {@code text} unchanged when PlaceholderAPI is absent or has nothing to do. */
    public String apply(Player player, String text) {
        if (!available || player == null || text == null || text.indexOf('%') < 0) return text;
        return PlaceholderBridge.set(player, text);
    }

    /**
     * Applies {@link #apply(Player, String)} to each line.
     *
     * <p>When nothing can change, the caller's own list is returned rather than a
     * copy: this runs for every menu render and most servers have no placeholders
     * in their lore. Callers must treat the result as immutable either way.
     */
    public List<String> apply(Player player, List<String> lines) {
        if (lines == null || lines.isEmpty()) return List.of();
        if (!available || player == null) return lines;
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(apply(player, line));
        return out;
    }
}
