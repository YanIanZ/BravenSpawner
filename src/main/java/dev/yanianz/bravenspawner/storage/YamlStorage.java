package dev.yanianz.bravenspawner.storage;

import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Default backend: one YAML file, replaced atomically on each flush. */
public final class YamlStorage implements Storage {

    private final Plugin plugin;
    private final File file;

    public YamlStorage(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "spawners.yml");
    }

    @Override
    public void init() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("could not create data folder");
        }
    }

    @Override
    public List<Spawner> loadAll() {
        List<Spawner> out = new ArrayList<>();
        if (!file.exists()) return out;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("spawners");
        if (root == null) return out;

        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            UUID id;
            UUID owner;
            try {
                id = UUID.fromString(key);
                owner = UUID.fromString(s.getString("owner", ""));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("skipping malformed spawner record " + key);
                continue;
            }
            Spawner spawner = new Spawner(
                    id, owner,
                    s.getString("world", "world"),
                    s.getInt("x"), s.getInt("y"), s.getInt("z"),
                    Storage.Codec.kindOf(s.getString("kind")),
                    s.getString("product", "ZOMBIE"),
                    s.getInt("stack", 1),
                    s.getInt("tier", 0),
                    s.getBoolean("hologram", true));
            spawner.trusted().addAll(Storage.Codec.decodeTrusted(s.getString("trusted")));
            spawner.replaceContents(Storage.Codec.decodeItems(s.getString("items")));
            spawner.addXp(s.getInt("xp", 0));
            spawner.produced(s.getLong("produced", 0));
            if (s.getBoolean("corrupted", false)) spawner.corrupt();
            out.add(spawner);
        }
        return out;
    }

    @Override
    public void upsert(Spawner spawner) {
        // whole-file backend: writes are batched by flushAll
    }

    @Override
    public void delete(UUID id) {
        // whole-file backend: writes are batched by flushAll
    }

    @Override
    public void flushAll(Collection<Spawner> spawners) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Spawner s : spawners) {
            String base = "spawners." + s.id();
            yaml.set(base + ".owner", s.owner().toString());
            yaml.set(base + ".world", s.world());
            yaml.set(base + ".x", s.x());
            yaml.set(base + ".y", s.y());
            yaml.set(base + ".z", s.z());
            yaml.set(base + ".kind", s.kind().name());
            yaml.set(base + ".product", s.product());
            yaml.set(base + ".stack", s.stack());
            yaml.set(base + ".tier", s.tier());
            yaml.set(base + ".hologram", s.hologram());
            yaml.set(base + ".corrupted", s.corrupted());
            yaml.set(base + ".xp", s.storedXp());
            yaml.set(base + ".produced", s.produced());
            yaml.set(base + ".trusted", Storage.Codec.encodeTrusted(s.trusted()));
            yaml.set(base + ".items", Storage.Codec.encodeItems(s.contents()));
        }
        try {
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            yaml.save(tmp);
            if (!tmp.renameTo(file)) {
                if (file.delete() && !tmp.renameTo(file)) {
                    plugin.getLogger().warning("could not replace spawners.yml");
                }
            }
        } catch (IOException e) {
            plugin.getLogger().severe("write failed: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        // nothing held open
    }
}
