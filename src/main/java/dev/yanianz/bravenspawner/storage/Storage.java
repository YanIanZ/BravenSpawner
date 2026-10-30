package dev.yanianz.bravenspawner.storage;

import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Persistence contract plus the serialisation shared by every backend.
 *
 * <p>Writes are tracked as a dirty set so a periodic checkpoint can decide whether
 * a durable pass is needed at all. Backends update that set from
 * {@link #upsert(Spawner)} and {@link #delete(UUID)}; {@link #flushAll(Collection)}
 * clears it only after a successful write.
 *
 * <p>{@link #isHealthy()} is a fail-closed gate: a backend that could not read its
 * previous state must never be asked to overwrite it, because the caller would
 * otherwise persist an empty or partial map over real data.
 */
public interface Storage extends AutoCloseable {

    void init();

    List<Spawner> loadAll();

    void upsert(Spawner spawner);

    void delete(UUID id);

    void flushAll(Collection<Spawner> spawners);

    /**
     * Flush with an explicit scope. {@code full=false} lets a backend write only the
     * records that changed; {@code full=true} rewrites every live record. Whole-file
     * backends treat both the same.
     */
    default void flushAll(Collection<Spawner> spawners, boolean full) {
        flushAll(spawners);
    }

    /**
     * False when the last load failed. A checkpoint must not flush while this is
     * false, otherwise the in-memory map (possibly empty) would replace durable
     * records that simply could not be read yet.
     */
    boolean isHealthy();

    /** True when records changed since the last successful flush. */
    boolean isDirty();

    /** Number of record ids waiting to be made durable; used for diagnostics. */
    int pendingWrites();

    @Override
    void close();

    /**
     * Unchecked failure from a backend. Storage methods are called from listeners
     * and the checkpoint thread, so a checked exception would be forced into a
     * catch-all anyway; callers log these at SEVERE instead of losing them.
     */
    final class StorageException extends RuntimeException {

        public StorageException(String message) {
            super(message);
        }

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Item stacks <-> base64, using Bukkit's own object streams. */
    final class Codec {

        /**
         * Upper bound on a decoded inventory. A corrupted length prefix must fail
         * closed instead of allocating an arbitrary list from an untrusted blob.
         */
        private static final int MAX_ITEMS = 4096;

        private static final Logger LOG = Logger.getLogger("BravenSpawner");

        private Codec() {}

        /**
         * Encodes a full inventory. Failure is thrown rather than encoded as an
         * empty string: an empty blob is a valid empty inventory and silently
         * substituting one would erase the stored contents on the next save.
         */
        public static String encodeItems(List<ItemStack> items) {
            if (items == null) throw new IllegalArgumentException("items must not be null");
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                 BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
                out.writeInt(items.size());
                for (ItemStack item : items) {
                    if (item == null) throw new IllegalArgumentException("inventory contains a null item");
                    out.writeObject(item);
                }
                out.flush();
                return Base64.getEncoder().encodeToString(bytes.toByteArray());
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("could not encode " + items.size() + " item stack(s)", e);
            }
        }

        /** Decodes a blob without a record id; see {@link #decodeItems(String, String)}. */
        public static List<ItemStack> decodeItems(String blob) {
            return decodeItems(blob, "unknown");
        }

        /**
         * Decodes a stored inventory; returns {@code null} when the blob is
         * malformed. Callers must quarantine that record instead of passing an
         * empty list to {@code replaceContents}, which would destroy the stored
         * contents on the next flush.
         *
         * @param recordId spawner id used in the diagnostic, when the backend has one
         */
        public static List<ItemStack> decodeItems(String blob, String recordId) {
            if (blob == null || blob.isBlank()) return new ArrayList<>();
            try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(blob));
                 BukkitObjectInputStream in = new BukkitObjectInputStream(bytes)) {
                int n = in.readInt();
                if (n < 0 || n > MAX_ITEMS) {
                    throw new IOException("inventory length " + n + " outside 0.." + MAX_ITEMS);
                }
                List<ItemStack> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    Object o = in.readObject();
                    if (!(o instanceof ItemStack stack)) {
                        throw new IOException("unexpected inventory element " + (o == null ? "null" : o.getClass()));
                    }
                    out.add(stack);
                }
                return out;
            } catch (Exception e) {
                LOG.warning("quarantined malformed inventory for record " + recordId + ": " + e);
                return null;
            }
        }

        /**
         * Joins the trust list. The set is a {@code synchronizedSet} that commands edit
         * on another thread, so it is copied under its own monitor before iterating;
         * iterating it unlocked can throw {@code ConcurrentModificationException} and
         * fail the whole checkpoint.
         */
        public static String encodeTrusted(Set<UUID> trusted) {
            if (trusted == null) return "";
            List<UUID> copy;
            synchronized (trusted) {
                copy = new ArrayList<>(trusted);
            }
            StringBuilder sb = new StringBuilder();
            for (UUID id : copy) {
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
            return sb.toString();
        }

        /**
         * Restores a persisted corruption flag and timestamp. Records written before
         * the timestamp was stored carry 0; those keep the old behaviour (corrupted
         * as of load time) instead of an epoch-zero timestamp.
         */
        public static void restoreCorruption(Spawner spawner, boolean corrupted, long corruptedAt) {
            if (corrupted && corruptedAt <= 0L) spawner.corrupt();
            else spawner.restoreCorruption(corrupted, corruptedAt);
        }

        public static Set<UUID> decodeTrusted(String raw) {
            Set<UUID> out = new LinkedHashSet<>();
            if (raw == null || raw.isBlank()) return out;
            for (String part : raw.split(",")) {
                try {
                    out.add(UUID.fromString(part.trim()));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entry
                }
            }
            return out;
        }

        public static Spawner.Kind kindOf(String raw) {
            try {
                return Spawner.Kind.valueOf((raw == null ? "MOB" : raw).toUpperCase());
            } catch (IllegalArgumentException e) {
                return Spawner.Kind.MOB;
            }
        }
    }
}
