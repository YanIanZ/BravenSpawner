package dev.yanianz.bravenspawner.storage;

import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default backend: one YAML file, replaced atomically on each checkpoint.
 *
 * <p>The format is whole-file, so {@link #upsert(Spawner)} and {@link #delete(UUID)}
 * only record that a write is pending; the checkpoint or shutdown drain performs
 * one atomic replacement. Records whose inventory blob cannot be decoded are kept
 * verbatim in a quarantine map and written back untouched, so a corrupt row is
 * never silently erased by the next flush.
 */
public final class YamlStorage implements Storage {

    private final Plugin plugin;
    private final File file;

    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final Set<UUID> tombstones = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> quarantined = new ConcurrentHashMap<>();

    private volatile boolean healthy = true;

    public YamlStorage(Plugin plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.file = new File(plugin.getDataFolder(), "spawners.yml");
    }

    @Override
    public void init() {
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            throw new StorageException("could not create data folder " + folder);
        }
    }

    @Override
    public List<Spawner> loadAll() {
        List<Spawner> out = new ArrayList<>();
        quarantined.clear();
        if (!file.exists()) {
            healthy = true;
            return out;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            healthy = false;
            plugin.getLogger().severe("spawners.yml could not be read; refusing to overwrite it: "
                    + e.getMessage());
            throw new StorageException("spawners.yml load failed", e);
        }

        ConfigurationSection root = yaml.getConfigurationSection("spawners");
        if (root == null) {
            healthy = true;
            return out;
        }

        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) {
                Object raw = root.get(key);
                if (raw != null) quarantined.put(key, raw);
                continue;
            }
            try {
                UUID id = UUID.fromString(key);
                UUID owner = UUID.fromString(s.getString("owner", ""));
                List<org.bukkit.inventory.ItemStack> items =
                        Storage.Codec.decodeItems(s.getString("items"), key);
                if (items == null) {
                    // Keep the raw section; an empty list here would overwrite the
                    // player's stored loot on the next durable flush.
                    quarantine(key, s);
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
                spawner.replaceContents(items);
                spawner.addXp(s.getInt("xp", 0));
                spawner.produced(s.getLong("produced", 0));
                if (s.getBoolean("corrupted", false)) spawner.corrupt();
                out.add(spawner);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("quarantined malformed spawner record " + key + ": " + e.getMessage());
                quarantine(key, s);
            }
        }
        healthy = true;
        return out;
    }

    private void quarantine(String key, ConfigurationSection section) {
        if (section == null) return;
        quarantined.put(key, section.getValues(true));
    }

    @Override
    public void upsert(Spawner spawner) {
        if (spawner == null) return;
        tombstones.remove(spawner.id());
        quarantined.remove(spawner.id().toString());
        dirty.add(spawner.id());
    }

    @Override
    public void delete(UUID id) {
        if (id == null) return;
        tombstones.add(id);
        quarantined.remove(id.toString());
        dirty.add(id);
    }

    @Override
    public void flushAll(Collection<Spawner> spawners) {
        if (!healthy) {
            // A failed load leaves the live map partial; writing it would erase the
            // records that were never read. Refuse until a load succeeds.
            throw new StorageException("refusing to overwrite spawners.yml after a failed load");
        }
        // Snapshot-and-clear before writing: a concurrent upsert that lands during
        // the write stays dirty and triggers another pass instead of being lost.
        Set<UUID> pending = new HashSet<>(dirty);
        dirty.removeAll(pending);

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            Set<String> written = new HashSet<>();
            for (Spawner s : spawners) {
                // A checkpoint may hold a snapshot taken before this delete; never
                // let that snapshot resurrect a record that was removed on purpose.
                if (tombstones.contains(s.id())) continue;
                writeRecord(yaml, s);
                written.add(s.id().toString());
            }
            for (Map.Entry<String, Object> entry : quarantined.entrySet()) {
                if (written.contains(entry.getKey())) continue;
                String path = "spawners." + entry.getKey();
                if (entry.getValue() instanceof Map<?, ?> section) {
                    yaml.createSection(path, section);
                } else {
                    yaml.set(path, entry.getValue());
                }
            }
            saveAtomically(yaml);
        } catch (RuntimeException e) {
            dirty.addAll(pending);
            plugin.getLogger().severe("spawners.yml write failed; previous file kept: " + e.getMessage());
            throw (e instanceof StorageException se)
                    ? se
                    : new StorageException("spawners.yml write failed", e);
        }
    }

    private void writeRecord(YamlConfiguration yaml, Spawner s) {
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

    /**
     * Temp file + fsync + atomic move. A crash mid-write leaves either the old
     * complete file or the new complete file, never a truncated one.
     */
    private void saveAtomically(YamlConfiguration yaml) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new StorageException("could not create data folder " + parent);
        }
        Path target = file.toPath();
        Path tmp = target.resolveSibling(file.getName() + ".tmp");
        try {
            yaml.save(tmp.toFile());
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new StorageException("could not replace " + file.getName(), e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // a stale temp file is harmless; the next flush overwrites it
            }
        }
    }

    @Override
    public boolean isHealthy() {
        return healthy;
    }

    @Override
    public boolean isDirty() {
        return !dirty.isEmpty();
    }

    @Override
    public int pendingWrites() {
        return dirty.size();
    }

    @Override
    public void close() {
        // nothing held open
    }
}
