package dev.yanianz.bravenspawner.platform;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.List;

/**
 * Follows worlds that load and unload while the server runs.
 *
 * <p>SourbyCraft's Aurora World Fabric (AWF) and InfernalSuite AdvancedSlimePaper
 * (ASP) keep each island in its own world, load it on demand and unload it when
 * idle. Both create ordinary Bukkit worlds, so the standard world events cover
 * them; SourbyCraft additionally fires Canvas's {@code WorldUnloadAsyncEvent},
 * which is registered by name when present (no compile dependency on sourbyapi).
 *
 * <p>Unloading pauses production and drops holograms of that world; loading
 * resumes production, restores missing blocks when that option is on, and
 * rebuilds holograms. Spawner records themselves are never touched: a world that
 * is merely unloaded keeps every stored item.
 */
public final class WorldLifecycle implements Listener {

    static final String CANVAS_ASYNC_UNLOAD = "io.canvasmc.canvas.event.world.WorldUnloadAsyncEvent";

    private final BravenSpawner plugin;

    public WorldLifecycle(BravenSpawner plugin) {
        this.plugin = plugin;
    }

    /** Registers the Bukkit handlers and, on SourbyCraft/Canvas, the async unload event. */
    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        Class<? extends Event> async = optionalEvent(CANVAS_ASYNC_UNLOAD);
        if (async == null) return;
        plugin.getServer().getPluginManager().registerEvent(async, this, EventPriority.MONITOR, (listener, event) -> {
            if (!(event instanceof WorldEvent world)) return;
            if (event instanceof Cancellable cancellable && cancellable.isCancelled()) return;
            unloaded(world.getWorld().getName());
        }, plugin, true);
        plugin.debug("world lifecycle: following " + async.getSimpleName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(WorldLoadEvent event) {
        loaded(event.getWorld().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnload(WorldUnloadEvent event) {
        unloaded(event.getWorld().getName());
    }

    void loaded(String world) {
        if (!plugin.ready() || plugin.manager() == null) return;
        List<Spawner> spawners = plugin.manager().inWorld(world);
        if (spawners.isEmpty()) return;
        plugin.manager().resumeWorld(world);
        dev.yanianz.bravenspawner.mechanics.RestoreMissing.reconcile(plugin, spawners);
        if (plugin.holograms() != null) spawners.forEach(plugin.holograms()::update);
        plugin.debug("world " + world + " loaded: resumed " + spawners.size() + " spawner(s)");
    }

    void unloaded(String world) {
        if (plugin.manager() == null) return;
        List<Spawner> spawners = plugin.manager().inWorld(world);
        if (spawners.isEmpty()) return;
        plugin.manager().pauseWorld(world);
        if (plugin.holograms() != null) for (Spawner spawner : spawners) plugin.holograms().remove(spawner.id());
        plugin.debug("world " + world + " unloading: paused " + spawners.size() + " spawner(s)");
    }

    /** The named event class when this server ships it, else null. */
    static Class<? extends Event> optionalEvent(String name) {
        try {
            Class<?> type = Class.forName(name, false, WorldLifecycle.class.getClassLoader());
            return WorldEvent.class.isAssignableFrom(type) ? type.asSubclass(Event.class) : null;
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }
}
