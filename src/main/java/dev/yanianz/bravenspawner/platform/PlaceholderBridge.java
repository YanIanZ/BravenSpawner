package dev.yanianz.bravenspawner.platform;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/**
 * The only class in the plugin that names PlaceholderAPI's type.
 *
 * <p>PlaceholderAPI is compileOnly and soft, so its class cannot be linked on a
 * server without it and the JVM raises {@code NoClassDefFoundError} at the first
 * resolved reference. One class, one catch: the rest of the plugin keeps calling
 * {@link Placeholders} and never sees a linkage failure.
 *
 * <p>A plugin-presence probe over a third-party API, not reflection over this
 * plugin's own code.
 */
final class PlaceholderBridge {

    private PlaceholderBridge() {}

    /**
     * Runs {@code text} through PlaceholderAPI's parser.
     *
     * <p>The input is returned unchanged on every failure path. Rendering a menu
     * with raw placeholders is a cosmetic bug; rendering nothing because a soft
     * dependency misbehaved is a broken GUI, so the fallback is always the text as
     * written.
     */
    static String set(Player player, String text) {
        try {
            return PlaceholderAPI.setPlaceholders(player, text);
        } catch (NoClassDefFoundError absent) {
            // PlaceholderAPI is not installed on this server.
            return text;
        } catch (Throwable failed) {
            // A placeholder expansion that throws must not take the menu with it.
            return text;
        }
    }
}
