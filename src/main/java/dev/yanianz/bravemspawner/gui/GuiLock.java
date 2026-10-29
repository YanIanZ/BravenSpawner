package dev.yanianz.bravemspawner.gui;

import dev.yanianz.bravemspawner.BravemSpawner;
import dev.yanianz.bravemspawner.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-dupe guard for the chest GUI, driven by {@code chest_gui.protection}.
 *
 * <p>A closed GUI can race an async storage flush: the player sees the window gone
 * while the spawner is still being written. Three independent gates cover that
 * window — a per-player/per-spawner reconnect cooldown, a short spawner lock that
 * keeps another player out until the save is done, and a session map that the
 * movement listener uses to resolve which spawner a player currently has open.
 *
 * <p>{@link #of(BravemSpawner)} is the shared instance. The plugin has no field for
 * this guard and the GUI classes may live in another module, so the instance is
 * cached weakly per plugin instead of adding state to {@code BravemSpawner}.
 *
 * <p>{@link #canOpen(UUID, UUID)} sends the refusal message itself. The existing
 * open path is a single choke point that cannot return a reason, and an operator
 * who only sees a silently ignored click will retry until the lock expires.
 */
public final class GuiLock {

    private static final Map<BravemSpawner, WeakReference<GuiLock>> SHARED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final BravemSpawner plugin;

    /** player + spawner -> epoch millis after which the GUI may be reopened. */
    private final Map<SessionKey, Long> reopenUntil = new ConcurrentHashMap<>();

    /** spawner -> epoch millis until which the post-close save owns it. */
    private final Map<UUID, Long> lockedUntil = new ConcurrentHashMap<>();

    /** player -> spawner currently open for that player. */
    private final Map<UUID, UUID> playerSpawners = new ConcurrentHashMap<>();

    /** spawner -> player currently holding it, used for clearer refusals. */
    private final Map<UUID, UUID> spawnerPlayers = new ConcurrentHashMap<>();

    public GuiLock(BravemSpawner plugin) {
        this.plugin = plugin;
    }

    /**
     * Returns the process-wide guard for this plugin, creating it on first use.
     * Callers that only need the shared state should prefer this over a fresh
     * constructor, otherwise the GUI and the movement listener would track
     * separate session maps.
     */
    public static GuiLock of(BravemSpawner plugin) {
        if (plugin == null) throw new IllegalArgumentException("plugin");
        synchronized (SHARED) {
            WeakReference<GuiLock> reference = SHARED.get(plugin);
            GuiLock existing = reference == null ? null : reference.get();
            if (existing != null) return existing;
            GuiLock created = new GuiLock(plugin);
            SHARED.put(plugin, new WeakReference<>(created));
            return created;
        }
    }

    /**
     * Locks a spawner after its GUI closes so an in-flight save can finish before
     * another player may touch the same storage.
     */
    public void lock(UUID spawnerId) {
        if (spawnerId == null) return;
        long timeout = lockTimeoutMs();
        if (timeout <= 0) {
            lockedUntil.remove(spawnerId);
            return;
        }
        lockedUntil.put(spawnerId, deadline(System.currentTimeMillis(), timeout));
    }

    /** True while the spawner is inside its post-close save window. */
    public boolean isLocked(UUID spawnerId) {
        if (spawnerId == null) return false;
        Long until = lockedUntil.get(spawnerId);
        if (until == null) return false;
        if (until > System.currentTimeMillis()) return true;
        lockedUntil.remove(spawnerId, until);
        return false;
    }

    /**
     * Gate the open path. Returns false and messages the player when the spawner
     * is locked, held by somebody else, or still inside this player's reconnect
     * cooldown. A null id is refused rather than throwing inside an event.
     */
    public boolean canOpen(UUID player, UUID spawnerId) {
        String denial = denialReason(player, spawnerId);
        if (denial == null) return true;
        if (player != null && Bukkit.getPlayer(player) instanceof Player online) {
            online.sendMessage(Text.of(denial));
        }
        return false;
    }

    /**
     * Returns the refusal text for {@link #canOpen(UUID, UUID)}, or null when the
     * open is allowed. Exposed so a future menu can render the same reason in its
     * own title instead of duplicating the rules.
     */
    public String denialReason(UUID player, UUID spawnerId) {
        if (player == null || spawnerId == null) {
            return "&cThat spawner is not available.";
        }

        long now = System.currentTimeMillis();

        Long locked = lockedUntil.get(spawnerId);
        if (locked != null) {
            if (locked > now) {
                return "&cThis spawner is still saving. Try again in &f"
                        + seconds(locked - now) + "s&c.";
            }
            lockedUntil.remove(spawnerId, locked);
        }

        UUID holder = spawnerPlayers.get(spawnerId);
        if (holder != null && !holder.equals(player)) {
            return "&cAnother player is using this spawner.";
        }

        SessionKey key = new SessionKey(player, spawnerId);
        Long reopen = reopenUntil.get(key);
        if (reopen != null) {
            if (reopen > now) {
                return "&cPlease wait &f" + seconds(reopen - now)
                        + "s&c before reopening this spawner.";
            }
            reopenUntil.remove(key, reopen);
        }
        return null;
    }

    /**
     * Records that the player has the spawner open. Also clears the post-close
     * lock and reconnect timer, because this is the successful side of the gate.
     */
    public void markOpened(UUID player, UUID spawnerId) {
        if (player == null || spawnerId == null) return;

        UUID previous = playerSpawners.put(player, spawnerId);
        if (previous != null && !previous.equals(spawnerId)) {
            spawnerPlayers.remove(previous, player);
            lock(previous);
        }
        spawnerPlayers.put(spawnerId, player);
        lockedUntil.remove(spawnerId);
        reopenUntil.remove(new SessionKey(player, spawnerId));
    }

    /**
     * Records that the player's GUI closed: starts the reconnect cooldown for this
     * pair and locks the spawner for the save timeout. No-op when the player had
     * no tracked session, so closing an unrelated chest never locks a spawner.
     */
    public void markClosed(UUID player) {
        if (player == null) return;
        UUID spawnerId = playerSpawners.remove(player);
        if (spawnerId == null) return;

        spawnerPlayers.remove(spawnerId, player);
        long cooldown = reconnectCooldownMs();
        if (cooldown > 0) {
            reopenUntil.put(new SessionKey(player, spawnerId),
                    deadline(System.currentTimeMillis(), cooldown));
        }
        lock(spawnerId);
    }

    /**
     * Drops every player-scoped entry. A live session is locked first so quitting
     * mid-GUI still gets the same save protection as a normal close.
     */
    public void clear(UUID player) {
        if (player == null) return;

        UUID spawnerId = playerSpawners.remove(player);
        if (spawnerId != null) {
            spawnerPlayers.remove(spawnerId, player);
            lock(spawnerId);
        }
        reopenUntil.keySet().removeIf(key -> key.player().equals(player));
        spawnerPlayers.entrySet().removeIf(entry -> entry.getValue().equals(player));
    }

    /** The spawner this player currently has open, or null. */
    public UUID sessionSpawner(UUID player) {
        return player == null ? null : playerSpawners.get(player);
    }

    /** The configured teleport leash in blocks; 0 or less disables the check. */
    public double maxTeleportDistance() {
        return Math.max(0.0,
                plugin.getConfig().getDouble("chest_gui.protection.max_teleport_distance", 100.0));
    }

    private long reconnectCooldownMs() {
        return Math.max(0L,
                plugin.getConfig().getLong("chest_gui.protection.reconnect_cooldown", 3000L));
    }

    private long lockTimeoutMs() {
        return Math.max(0L,
                plugin.getConfig().getLong("chest_gui.protection.spawner_lock_timeout", 5000L));
    }

    private static long deadline(long now, long delta) {
        if (delta <= 0) return 0L;
        long value = now + delta;
        return value < now ? Long.MAX_VALUE : value;
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1f", Math.max(0L, millis) / 1000.0);
    }

    private record SessionKey(UUID player, UUID spawner) { }
}
