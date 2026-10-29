package dev.yanianz.bravenspawner.platform;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * The only class in the plugin that names Floodgate's API type.
 *
 * <p>Floodgate is compileOnly and soft: on a server without it the class file for
 * {@link FloodgateApi} cannot be linked, and the JVM raises
 * {@code NoClassDefFoundError} the first time a reference is resolved. Keeping the
 * reference inside one method means the failure surfaces here, at a call site with
 * a catch around it, instead of in {@link PlatformDetector} — a class that cannot
 * link is a class that cannot be loaded, and one unlinkable class would take the
 * whole plugin down at enable.
 *
 * <p>These are plugin-presence probes over a third-party API. Nothing here reaches
 * into Bukkit internals or into this plugin's own classes.
 */
final class BedrockProbe {

    private BedrockProbe() {}

    /**
     * Asks Floodgate whether it owns this player.
     *
     * <p>The question is answered through the plugin instance rather than a static
     * accessor because Floodgate's main class implements the API interface; that
     * link has been stable across every 2.x release, while static entry points have
     * moved. A missing plugin, a missing class and a Floodgate that throws all mean
     * the same thing to the caller — not a Bedrock player — so every path returns
     * {@code false} rather than failing the GUI open.
     */
    static boolean isFloodgatePlayer(Player player) {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("floodgate");
            return plugin instanceof FloodgateApi api && api.isFloodgatePlayer(player.getUniqueId());
        } catch (NoClassDefFoundError absent) {
            // Floodgate is not installed on this server.
            return false;
        } catch (Throwable failed) {
            // A half-loaded or broken Floodgate must not poison the caller: one
            // player sees the Java layout, and the next GUI open tries again.
            return false;
        }
    }
}
