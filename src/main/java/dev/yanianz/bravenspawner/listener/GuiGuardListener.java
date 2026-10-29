package dev.yanianz.bravenspawner.listener;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.gui.GuiLock;
import dev.yanianz.bravenspawner.gui.SpawnerGui;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teleport leash and session cleanup for the chest GUI.
 *
 * <p>{@link PlayerMoveEvent} fires every tick, and most moves have nothing to do
 * with a spawner. The handler therefore exits on the first cheap test (no
 * {@link SpawnerGui} session) and only performs the squared-distance check once
 * per ten ticks per player. The spawner identity comes from {@link GuiLock}
 * because {@code SpawnerGui}'s own session type is private.
 *
 * <p>Registration is the caller's job: this plugin's bootstrapper may only be
 * edited by the integration task, so {@code GuiGuardListener} stays a plain
 * {@link Listener} with a public constructor.
 */
public final class GuiGuardListener implements Listener {

    private static final int CHECK_INTERVAL_TICKS = 10;

    private final BravenSpawner plugin;
    private final GuiLock guiLock;
    private final Map<UUID, Long> lastCheck = new ConcurrentHashMap<>();

    public GuiGuardListener(BravenSpawner plugin) {
        this.plugin = plugin;
        this.guiLock = GuiLock.of(plugin);
    }

    /** The shared guard, so the GUI open path can use the same session state. */
    public GuiLock guiLock() {
        return guiLock;
    }

    /**
     * Closes the GUI when the player walks or teleports past
     * {@code max_teleport_distance}. Cancelled moves are ignored: a plugin that
     * blocks the move has not moved the player.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        SpawnerGui gui = plugin.gui();
        if (gui == null || gui.session(player) == null) {
            lastCheck.remove(playerId);
            return;
        }
        if (event.getTo() == null) return;

        long now = Bukkit.getCurrentTick();
        Long last = lastCheck.get(playerId);
        if (last != null && now >= last && now - last < CHECK_INTERVAL_TICKS) return;
        lastCheck.put(playerId, now);

        UUID spawnerId = guiLock.sessionSpawner(playerId);
        if (spawnerId == null) return;

        Spawner spawner = plugin.manager() == null ? null : plugin.manager().byId(spawnerId);
        if (spawner == null) {
            player.closeInventory();
            lastCheck.remove(playerId);
            return;
        }

        double max = guiLock.maxTeleportDistance();
        if (max <= 0.0) return;

        double dx = event.getTo().getX() - (spawner.x() + 0.5);
        double dy = event.getTo().getY() - (spawner.y() + 0.5);
        double dz = event.getTo().getZ() - (spawner.z() + 0.5);
        boolean otherWorld = !player.getWorld().getName().equals(spawner.world());
        if (!otherWorld && dx * dx + dy * dy + dz * dz <= max * max) return;

        player.sendMessage(Text.of("&cYou moved too far from the spawner; the menu was closed."));
        player.closeInventory();
        lastCheck.remove(playerId);
    }

    /**
     * Releases every player-scoped entry. A still-open session is locked for the
     * save timeout first, otherwise quitting would bypass the anti-dupe window.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        lastCheck.remove(playerId);
        guiLock.clear(playerId);
    }

    /** Starts the reconnect cooldown and save lock as soon as the window closes. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID playerId = player.getUniqueId();
        lastCheck.remove(playerId);
        guiLock.markClosed(playerId);
    }
}
