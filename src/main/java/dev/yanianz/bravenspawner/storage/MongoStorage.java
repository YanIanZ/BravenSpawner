package dev.yanianz.bravenspawner.storage;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bson.Document;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Incremental writes to Braven's own Mongo schema; gameplay methods only enqueue identifiers. */
public final class MongoStorage implements Storage {
    private final ConfigurationSection section;
    private final Logger logger;
    private final boolean injected;
    private final Object queueLock = new Object();
    private final Object ioLock = new Object();
    private final Map<UUID, Long> dirty = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingDeletes = new ConcurrentHashMap<>();
    private final Set<UUID> tombstones = ConcurrentHashMap.newKeySet();
    private volatile Set<UUID> quarantined = Set.of();
    private long generation;
    private volatile boolean healthy;
    private volatile boolean initialized;
    private MongoClient client;
    private MongoCollection<Document> collection;

    /** Does not create a client or touch a server; only init() does so for the selected backend. */
    public MongoStorage(Plugin plugin, ConfigurationSection section) {
        this.section = Objects.requireNonNull(section, "section");
        this.logger = Objects.requireNonNull(plugin, "plugin").getLogger();
        this.injected = false;
    }

    /** Driver boundary for isolated tests; never creates a MongoClient or network connection. */
    MongoStorage(MongoCollection<Document> collection, Logger logger) {
        this.collection = Objects.requireNonNull(collection, "collection");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.section = null;
        this.injected = true;
    }

    @Override public void init() {
        synchronized (ioLock) {
            if (initialized) return;
            if (injected) { initialized = true; healthy = false; return; }
            try {
                String uri = section.getString("mongodb.uri", "mongodb://localhost:27017/?serverSelectionTimeoutMS=5000&connectTimeoutMS=5000");
                String database = section.getString("mongodb.database", "bravenspawner");
                String name = section.getString("mongodb.collection", "braven_spawners");
                if (uri == null || uri.isBlank() || database == null || database.isBlank() || name == null || name.isBlank()) {
                    throw new IllegalArgumentException("Mongo URI, database and collection must be set");
                }
                client = MongoClients.create(uri);
                var db = client.getDatabase(database);
                db.runCommand(new Document("ping", 1));
                collection = db.getCollection(name);
                initialized = true;
                // Do not enable writes until the existing collection has been read.
                healthy = false;
            } catch (RuntimeException exception) {
                healthy = false;
                if (client != null) { client.close(); client = null; }
                throw new StorageException("Mongo initialization failed", exception);
            }
        }
    }

    @Override public List<Spawner> loadAll() {
        synchronized (ioLock) {
            requireInitialized();
            healthy = false;
            List<Spawner> loaded = new ArrayList<>();
            Set<UUID> protectedIds = new HashSet<>();
            try (var cursor = collection.find().iterator()) {
                while (cursor.hasNext()) {
                    Document document = cursor.next();
                    try { loaded.add(SpawnerDocumentCodec.decode(document)); }
                    catch (RuntimeException malformed) {
                        UUID id = documentId(document);
                        if (id != null) protectedIds.add(id);
                        // Never log a row or its inventory blob, which can contain user data.
                        logger.warning("Quarantined malformed Mongo spawner " + (id == null ? "with invalid id" : id)
                                + "; original document retained.");
                    }
                }
                quarantined = Set.copyOf(protectedIds);
                healthy = true;
                return loaded;
            } catch (RuntimeException exception) {
                healthy = false;
                throw new StorageException("Mongo load failed; writes disabled", exception);
            }
        }
    }

    private static UUID documentId(Document document) {
        if (document == null) return null;
        Object id = document.get("_id");
        if (!(id instanceof String value)) return null;
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException malformed) { return null; }
    }

    @Override public void upsert(Spawner spawner) {
        Objects.requireNonNull(spawner, "spawner");
        UUID id = spawner.id();
        synchronized (queueLock) {
            if (!tombstones.contains(id) && !quarantined.contains(id)) dirty.put(id, ++generation);
        }
    }

    @Override public void delete(UUID id) {
        Objects.requireNonNull(id, "id");
        synchronized (queueLock) {
            if (quarantined.contains(id)) return;
            tombstones.add(id);
            dirty.remove(id);
            pendingDeletes.put(id, ++generation);
        }
    }

    @Override public void flushAll(Collection<Spawner> spawners) {
        synchronized (ioLock) {
            requireInitialized();
            if (!healthy) throw new StorageException("Mongo state was not loaded successfully; refusing flush");
            Map<UUID, Spawner> live = new HashMap<>();
            for (Spawner spawner : spawners) if (spawner != null) live.put(spawner.id(), spawner);
            Map<UUID, Long> batch;
            synchronized (queueLock) { batch = Map.copyOf(dirty); }
            try {
                for (var entry : batch.entrySet()) {
                    UUID id = entry.getKey();
                    if (tombstones.contains(id) || quarantined.contains(id)) {
                        dirty.remove(id, entry.getValue());
                        continue;
                    }
                    Spawner spawner = live.get(id);
                    // Absence is not a deletion; retain the mark until a valid snapshot is available.
                    if (spawner == null) continue;
                    Document snapshot = SpawnerDocumentCodec.encode(spawner);
                    var result = collection.replaceOne(Filters.eq("_id", id.toString()), snapshot, new ReplaceOptions().upsert(true));
                    if (result == null || !result.wasAcknowledged()) throw new StorageException("Mongo replacement was not acknowledged");
                    dirty.remove(id, entry.getValue());
                }
                // Capture after upserts: a concurrent deletion during a replacement is
                // removed in this same pass, preventing stale snapshots from resurrecting it.
                Map<UUID, Long> deletes;
                synchronized (queueLock) { deletes = Map.copyOf(pendingDeletes); }
                for (var entry : deletes.entrySet()) {
                    UUID id = entry.getKey();
                    if (quarantined.contains(id)) { pendingDeletes.remove(id, entry.getValue()); continue; }
                    var result = collection.deleteOne(Filters.eq("_id", id.toString()));
                    if (result == null || !result.wasAcknowledged()) throw new StorageException("Mongo deletion was not acknowledged");
                    pendingDeletes.remove(id, entry.getValue());
                }
            } catch (RuntimeException exception) {
                throw new StorageException("Mongo checkpoint failed; unacknowledged changes remain queued", exception);
            }
        }
    }

    private void requireInitialized() {
        if (!initialized || collection == null) throw new StorageException("Mongo backend is not initialized");
    }

    @Override public boolean isHealthy() { return healthy; }
    @Override public boolean isDirty() { return !dirty.isEmpty() || !pendingDeletes.isEmpty(); }
    @Override public int pendingWrites() { return dirty.size() + pendingDeletes.size(); }

    @Override public void close() {
        synchronized (ioLock) {
            healthy = false;
            initialized = false;
            if (client != null) { client.close(); client = null; }
        }
    }
}
