package dev.yanianz.bravenspawner.events;

import dev.yanianz.bravenspawner.util.Text;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** Runtime adapter for admin-authored other/events.yml; all state is in EventEngine. */
public final class SpawnerEvents implements AutoCloseable {
    private static final String RESOURCE = "other/events.yml";
    private final JavaPlugin plugin;
    private final File file;
    private final EventEngine engine;
    private ScheduledTask task;
    private volatile boolean closed;

    public SpawnerEvents(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        file = new File(plugin.getDataFolder(), RESOURCE);
        engine = new EventEngine(readSettings(), Clock.systemDefaultZone(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    /** Starts a single global scheduler task. Disabled configs remain reloadable. */
    public synchronized void start() {
        if (task != null) return;
        closed = false;
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                ignored -> { if (!closed) deliver(engine.tick()); }, 20L, 20L);
    }

    /** Replaces the configuration and silently clears old boosts, matching the reference. */
    public synchronized void reload() {
        List<EventEngine.Transition> ended = engine.configure(readSettings());
        if (!ended.isEmpty() && !closed) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> deliver(ended));
        }
    }

    /** Live values exclude expired events even when a scheduler tick was delayed. */
    public double xpMultiplier() { return closed ? 1.0 : engine.xpMultiplier(); }
    public double cashMultiplier() { return closed ? 1.0 : engine.cashMultiplier(); }
    public List<EventEngine.ActiveEvent> activeEvents() { return closed ? List.of() : engine.activeEvents(); }

    /** Disabling discards active events without dispatching shutdown commands, matching the reference. */
    @Override public synchronized void close() {
        closed = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
        engine.clear();
    }

    private EventSettings readSettings() {
        try {
            if (!file.isFile()) plugin.saveResource(RESOURCE, false);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file);
            return EventSettings.parse(yaml, message -> plugin.getLogger().warning("Events: " + message));
        } catch (Exception failure) {
            plugin.getLogger().warning("Could not load " + RESOURCE + ": " + failure.getMessage());
            return new EventSettings(false, 300, 0, false, 1, false, List.of(), List.of(), List.of());
        }
    }

    /** Called only from the global scheduler; players receive messages on their entity scheduler. */
    private void deliver(List<EventEngine.Transition> transitions) {
        if (closed) return;
        for (EventEngine.Transition transition : transitions) {
            if (!transition.messages().isEmpty()) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.getScheduler().execute(plugin, () -> {
                        if (closed) return;
                        for (String line : transition.messages()) player.sendMessage(Text.of(line));
                    }, null, 1L);
                }
            }
            for (String configured : transition.commands()) {
                String command = configured.strip();
                if (command.startsWith("/")) command = command.substring(1);
                if (command.isBlank()) continue;
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                } catch (RuntimeException failure) {
                    plugin.getLogger().warning("Event '" + transition.definition().id()
                            + "' command failed: " + failure.getMessage());
                }
            }
        }
    }
}
