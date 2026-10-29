package dev.yanianz.bravemspawner.holo;

import dev.yanianz.bravemspawner.BravemSpawner;
import dev.yanianz.bravemspawner.model.Spawner;
import dev.yanianz.bravemspawner.util.Schedulers;
import dev.yanianz.bravemspawner.util.Text;
import org.bukkit.Location;
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

    private final BravemSpawner plugin;
    private final Map<UUID, UUID> displays = new ConcurrentHashMap<>();
    private final NamespacedKey key;

    public Holograms(BravemSpawner plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "hologram");
    }

    public void update(Spawner spawner) {
        if (!spawner.hologram()) {
            remove(spawner.id());
            return;
        }
        Location location = spawner.location();
        if (location == null) return;

        String line = plugin.getConfig().getString("hologram.line", "&e%product% &7| &f%stored% &7stored")
                .replace("%product%", spawner.product())
                .replace("%stored%", String.valueOf(spawner.storedCount()))
                .replace("%stack%", String.valueOf(spawner.stack()))
                .replace("%owner%", spawner.owner().toString().substring(0, 8));

        Schedulers.at(plugin, location, () -> {
            TextDisplay display = displays.containsKey(spawner.id())
                    ? plugin.getServer().getEntity(displays.get(spawner.id())) instanceof TextDisplay t ? t : null
                    : null;
            if (display == null) {
                display = location.getWorld().spawn(location.clone().add(0, 1.3, 0), TextDisplay.class);
                display.setBillboard(Display.Billboard.CENTER);
                display.setSeeThrough(true);
                display.getPersistentDataContainer().set(key, PersistentDataType.STRING, spawner.id().toString());
                displays.put(spawner.id(), display.getUniqueId());
            }
            display.text(Text.of(line));
        });
    }

    public void remove(UUID spawnerId) {
        UUID entityId = displays.remove(spawnerId);
        if (entityId == null) return;
        var entity = plugin.getServer().getEntity(entityId);
        if (entity != null) entity.remove();
    }

    public void removeAll() {
        displays.values().forEach(id -> {
            var entity = plugin.getServer().getEntity(id);
            if (entity != null) entity.remove();
        });
        displays.clear();
    }

    public boolean toggle(Spawner spawner) {
        spawner.hologram(!spawner.hologram());
        if (spawner.hologram()) update(spawner);
        else remove(spawner.id());
        return spawner.hologram();
    }

    public void refreshAll() {
        plugin.manager().all().forEach(this::update);
    }
}
