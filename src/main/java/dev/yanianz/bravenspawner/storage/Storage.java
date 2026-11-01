package dev.yanianz.bravenspawner.storage;

import dev.yanianz.bravenspawner.model.Spawner;
import dev.yanianz.bravenspawner.model.StockEntry;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
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

    /**
     * Message for a failed write: every distinct message in the cause chain, so a
     * wrapped codec failure (spawner id, item, root cause) is visible in one log line.
     */
    static String describeFailure(Throwable e) {
        if (e == null) return "unknown error";
        StringBuilder out = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            if (out.indexOf(message) >= 0) continue;
            if (out.length() > 0) out.append(" <- ");
            out.append(message);
        }
        return out.toString();
    }

    /** Item stacks <-> base64; see {@link ItemCodec} for the binary format. */
    final class Codec {

        private static final Logger LOG = Logger.getLogger("BravenSpawner");

        private Codec() {}

        /** Encodes without a record id; see {@link #encodeItems(List, String)}. */
        public static String encodeItems(List<ItemStack> items) {
            return encodeItems(items, "unknown");
        }

        /**
         * Encodes a full inventory in format v2. Failure is thrown rather than
         * encoded as an empty string: an empty blob is a valid empty inventory and
         * silently substituting one would erase the stored contents on the next save.
         *
         * @param recordId spawner id named in the failure message
         */
        public static String encodeItems(List<ItemStack> items, String recordId) {
            if (items == null) throw new IllegalArgumentException("items must not be null");
            try {
                return Base64.getEncoder().encodeToString(ItemCodec.encode(items));
            } catch (RuntimeException e) {
                throw new IllegalStateException("could not encode " + items.size() + " item stack(s) for spawner "
                        + recordId + ": " + (e.getMessage() == null ? ItemCodec.rootMessage(e) : e.getMessage()), e);
            }
        }

        /**
         * Encodes exact-count stock in format v2. Like {@link #encodeItems(List, String)},
         * a failure is thrown rather than written as an empty store.
         */
        public static String encodeStock(List<StockEntry> stock, String recordId) {
            if (stock == null) throw new IllegalArgumentException("stock must not be null");
            try {
                return Base64.getEncoder().encodeToString(ItemCodec.encodeStock(stock));
            } catch (RuntimeException e) {
                throw new IllegalStateException("could not encode " + stock.size() + " stock entr(ies) for spawner "
                        + recordId + ": " + (e.getMessage() == null ? ItemCodec.rootMessage(e) : e.getMessage()), e);
            }
        }

        /**
         * Decodes stored stock (format v2, or a legacy object stream) with exact
         * quantities; returns {@code null} when the blob is malformed so the caller
         * quarantines the record instead of loading an empty store.
         */
        public static List<StockEntry> decodeStock(String blob, String recordId) {
            if (blob == null || blob.isBlank()) return new ArrayList<>();
            try {
                byte[] raw = Base64.getDecoder().decode(blob);
                return ItemCodec.isV2(raw) ? ItemCodec.decodeStock(raw) : ItemCodec.legacyStock(ItemCodec.decodeLegacy(raw));
            } catch (Exception e) {
                LOG.warning("quarantined malformed inventory for record " + recordId + ": " + e);
                return null;
            }
        }

        /** Decodes a blob without a record id; see {@link #decodeItems(String, String)}. */
        public static List<ItemStack> decodeItems(String blob) {
            return decodeItems(blob, "unknown");
        }

        /**
         * Decodes a stored inventory (format v2 or a legacy object-stream blob);
         * returns {@code null} when the blob is malformed. Callers must quarantine
         * that record instead of passing an empty list to {@code replaceContents},
         * which would destroy the stored contents on the next flush.
         *
         * @param recordId spawner id used in the diagnostic, when the backend has one
         */
        public static List<ItemStack> decodeItems(String blob, String recordId) {
            if (blob == null || blob.isBlank()) return new ArrayList<>();
            try {
                byte[] raw = Base64.getDecoder().decode(blob);
                return ItemCodec.isV2(raw) ? ItemCodec.decode(raw) : ItemCodec.decodeLegacy(raw);
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
