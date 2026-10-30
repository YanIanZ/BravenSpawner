package dev.yanianz.bravenspawner.storage;

import dev.yanianz.bravenspawner.model.Spawner;
import org.bson.Document;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** BravenSpawner's own Mongo schema. This is not a decoder for another plugin's database. */
public final class SpawnerDocumentCodec {
    public static final int SCHEMA_VERSION = 1;
    private SpawnerDocumentCodec() { }

    public static Document encode(Spawner spawner) {
        synchronized (spawner) {
            List<String> trusted;
            synchronized (spawner.trusted()) {
                trusted = spawner.trusted().stream().map(UUID::toString).toList();
            }
            return new Document("_id", spawner.id().toString())
                    .append("schema_version", SCHEMA_VERSION)
                    .append("owner", spawner.owner().toString()).append("world", spawner.world())
                    .append("x", spawner.x()).append("y", spawner.y()).append("z", spawner.z())
                    .append("kind", spawner.kind().name()).append("product", spawner.product())
                    .append("stack", spawner.stack()).append("tier", spawner.tier())
                    .append("hologram", spawner.hologram()).append("corrupted", spawner.corrupted())
                    .append("corrupted_at", spawner.corruptedAt()).append("xp", spawner.storedXp())
                    .append("produced", spawner.produced()).append("pending", spawner.pending())
                    .append("trusted", trusted).append("items", Storage.Codec.encodeItems(spawner.contents(), spawner.id().toString()))
                    .append("updated_at", System.currentTimeMillis());
        }
    }

    /** Malformed rows throw, allowing the backend to quarantine the original document in place. */
    public static Spawner decode(Document document) {
        if (integer(document, "schema_version", 1, Integer.MAX_VALUE) != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported Braven schema version");
        }
        UUID id = UUID.fromString(text(document, "_id"));
        UUID owner = UUID.fromString(text(document, "owner"));
        Spawner.Kind kind = Spawner.Kind.valueOf(text(document, "kind"));
        Spawner spawner = new Spawner(id, owner, text(document, "world"),
                integer(document, "x", Integer.MIN_VALUE, Integer.MAX_VALUE),
                integer(document, "y", Integer.MIN_VALUE, Integer.MAX_VALUE),
                integer(document, "z", Integer.MIN_VALUE, Integer.MAX_VALUE), kind, text(document, "product"),
                integer(document, "stack", 1, Integer.MAX_VALUE), integer(document, "tier", 0, Integer.MAX_VALUE),
                flag(document, "hologram"));
        spawner.restoreCorruption(flag(document, "corrupted"), number(document, "corrupted_at", 0, Long.MAX_VALUE));
        spawner.addXp(integer(document, "xp", 0, Integer.MAX_VALUE));
        spawner.produced(number(document, "produced", 0, Long.MAX_VALUE));
        spawner.pending(integer(document, "pending", 0, Integer.MAX_VALUE));
        Object trusted = document.get("trusted");
        if (!(trusted instanceof List<?> entries)) throw new IllegalArgumentException("trusted must be a list");
        for (Object entry : entries) {
            if (!(entry instanceof String value)) throw new IllegalArgumentException("trusted contains a non-UUID");
            spawner.trusted().add(UUID.fromString(value));
        }
        Object blob = document.get("items");
        if (!(blob instanceof String value) || value.isBlank()) throw new IllegalArgumentException("items must be a nonempty encoded string");
        var contents = Storage.Codec.decodeItems(value, id.toString());
        if (contents == null) throw new IllegalArgumentException("malformed inventory blob");
        spawner.replaceContents(new ArrayList<>(contents));
        return spawner;
    }

    private static String text(Document document, String key) {
        Object value = document.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("invalid " + key);
        return text;
    }

    private static boolean flag(Document document, String key) {
        Object value = document.get(key);
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException("invalid " + key);
        return flag;
    }

    private static int integer(Document document, String key, long minimum, long maximum) {
        return (int) number(document, key, minimum, maximum);
    }

    private static long number(Document document, String key, long minimum, long maximum) {
        Object value = document.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("invalid " + key);
        long number = ((Number) value).longValue();
        if (number < minimum || number > maximum) throw new IllegalArgumentException("out of range " + key);
        return number;
    }
}
