package dev.yanianz.bravenspawner.holo;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.util.Schedulers;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holograms built on TextDisplay, so no external hologram plugin is required and
 * Folia's entity rules are respected: spawn and update happen on the region that
 * owns the location.
 */
public final class Holograms {

    private final BravenSpawner plugin;
    private final Map<UUID, TextDisplay> displays = new ConcurrentHashMap<>();
    private final Map<UUID, Object> generations = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();
    private volatile boolean stopped;
    private final NamespacedKey key;
    private volatile dev.yanianz.bravenspawner.platform.HologramConfig config;

    public Holograms(BravenSpawner plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "hologram");
        this.config = dev.yanianz.bravenspawner.platform.HologramConfig.load(plugin);
    }

    public void update(Spawner spawner) {
        if (!live(spawner)) return;
        if (!spawner.hologram()) {
            remove(spawner.id());
            return;
        }
        Location location = spawner.location();
        if (location == null) return;

        var definition = plugin.product(spawner);
        var lines = config.lines(spawner, definition == null ? Map.of() : Map.of("product", definition.displayName()));
        if (lines.isEmpty()) { remove(spawner.id()); return; }
        String line = String.join("\n", lines);

        Object generation;
        synchronized (lifecycle) {
            if (!live(spawner)) return;
            generation = generations.computeIfAbsent(spawner.id(), ignored -> new Object());
        }
        try {
            Schedulers.at(plugin, location, () -> {
                TextDisplay display;
                synchronized (lifecycle) {
                    if (!current(spawner, generation)) return;
                    display = displays.get(spawner.id());
                    if (display == null) {
                        display = location.getWorld().spawn(location.clone().add(0, 1.3, 0), TextDisplay.class);
                        display.setPersistent(false);
                        display.setBillboard(Display.Billboard.CENTER);
                        display.setSeeThrough(true);
                        display.getPersistentDataContainer().set(key, PersistentDataType.STRING, spawner.id().toString());
                        displays.put(spawner.id(), display);
                    }
                }
                TextDisplay target = display;
                try {
                    boolean scheduled = target.getScheduler().execute(plugin, () -> {
                        if (!current(spawner, generation) || displays.get(spawner.id()) != target) return;
                        target.setViewRange(config.viewDistance() / 64.0f);
                        target.text(Text.of(line));
                    }, () -> displays.remove(spawner.id(), target), 1L);
                    if (!scheduled) displays.remove(spawner.id(), target);
                } catch (RuntimeException e) {
                    if (plugin.isEnabled()) plugin.getLogger().warning("could not schedule hologram update: " + e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            if (plugin.isEnabled()) plugin.getLogger().warning("could not schedule hologram: " + e.getMessage());
        }
    }

    private boolean live(Spawner spawner) {
        return !stopped && plugin.isEnabled() && plugin.manager() != null
                && plugin.manager().byId(spawner.id()) == spawner;
    }

    private boolean current(Spawner spawner, Object generation) {
        return live(spawner) && spawner.hologram() && generations.get(spawner.id()) == generation;
    }

    public void remove(UUID spawnerId) {
        TextDisplay display;
        synchronized (lifecycle) {
            generations.remove(spawnerId);
            display = displays.remove(spawnerId);
        }
        if (display != null) retire(display);
    }

    public void removeAll() {
        java.util.List<TextDisplay> retired;
        synchronized (lifecycle) {
            stopped = true;
            generations.clear();
            retired = java.util.List.copyOf(displays.values());
            displays.clear();
        }
        retired.forEach(this::retire);
    }

    private void retire(TextDisplay display) {
        try {
            if (Bukkit.isOwnedByCurrentRegion(display)) display.remove();
            else if (!display.getScheduler().execute(plugin, display::remove, null, 1L)) {
                plugin.getLogger().fine("hologram entity already retired: " + display.getUniqueId());
            }
        } catch (RuntimeException e) {
            // During plugin shutdown Folia may already reject scheduling. Never
            // remove from a foreign region; nonpersistent displays are not saved.
            plugin.getLogger().warning("could not schedule hologram removal " + display.getUniqueId()
                    + "; display is nonpersistent: " + e.getMessage());
        }
    }

    public boolean toggle(Spawner spawner) {
        spawner.hologram(!spawner.hologram());
        if (spawner.hologram()) update(spawner);
        else remove(spawner.id());
        return spawner.hologram();
    }

    public void reload() {
        config = dev.yanianz.bravenspawner.platform.HologramConfig.load(plugin);
        refreshAll();
    }

    public void refreshAll() {
        plugin.manager().all().forEach(this::update);
    }
}
