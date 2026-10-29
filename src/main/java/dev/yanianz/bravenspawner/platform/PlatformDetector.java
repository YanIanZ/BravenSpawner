package dev.yanianz.bravenspawner.platform;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Per-player answer to "is this a Bedrock client?".
 *
 * <p>Plugins cannot be installed into a running server, so presence is resolved
 * once at enable and every later call is a field read plus at most one UUID
 * comparison. That matters because the GUI asks this on each open, on the region
 * thread, and a plugin-manager scan per open would be waste.
 *
 * <p>Order is fixed: Floodgate first because it knows its own players exactly,
 * then the Geyser UUID shape. The prefix alone is not evidence — an offline-mode
 * server can hand out a UUID that starts the same way — so it is only trusted when
 * Geyser is actually installed.
 */
public final class PlatformDetector {

    /**
     * Geyser builds a Bedrock player's UUID as
     * {@code 00000000-0000-0000-xxxx-xxxxxxxxxxxx}; the tail groups carry the XUID.
     */
    private static final String GEYSER_UUID_PREFIX = "00000000-0000-0000-";

    private final boolean configured;
    private final boolean floodgate;
    private final boolean geyser;

    public PlatformDetector(BravenSpawner plugin) {
        Settings settings = plugin == null ? null : plugin.settings();
        this.configured = settings != null && settings.bedrock;
        this.floodgate = present("floodgate");
        this.geyser = present("Geyser-Spigot") || present("Geyser");
    }

    private static boolean present(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }

    /**
     * Whether config.yml {@code bedrock:} opted into Bedrock-specific layouts.
     *
     * <p>Separate from {@link #isBedrock(Player)} on purpose: the switch is policy
     * (which menu to draw) and the method is fact (which client is connected). A
     * caller that has already honoured the switch should not pay for it twice, and
     * a caller that has not should still be able to ask.
     */
    public boolean configured() { return configured; }

    public boolean floodgateAvailable() { return floodgate; }

    public boolean geyserAvailable() { return geyser; }

    /**
     * True when {@code player} is connected through a Bedrock client.
     *
     * <p>Null-safe because menu code calls this from inventory holders that may
     * outlive the viewer.
     */
    public boolean isBedrock(Player player) {
        if (player == null) return false;
        if (floodgate) {
            try {
                return BedrockProbe.isFloodgatePlayer(player);
            } catch (Throwable linkage) {
                // A JVM that resolves FloodgateApi while verifying the probe would
                // throw at this call site rather than inside the probe's own catch.
                return false;
            }
        }
        return geyser && isGeyserUuid(player.getUniqueId());
    }

    private static boolean isGeyserUuid(UUID id) {
        if (id == null) return false;
        String text = id.toString();
        return text.length() >= GEYSER_UUID_PREFIX.length()
                && text.regionMatches(true, 0, GEYSER_UUID_PREFIX, 0, GEYSER_UUID_PREFIX.length());
    }
}
