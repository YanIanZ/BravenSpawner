package dev.yanianz.bravenspawner.storage;

import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Persistence contract plus the serialisation shared by every backend. */
public interface Storage extends AutoCloseable {

    void init();

    List<Spawner> loadAll();

    void upsert(Spawner spawner);

    void delete(UUID id);

    void flushAll(Collection<Spawner> spawners);

    @Override
    void close();

    /** Item stacks <-> base64, using Bukkit's own object streams. */
    final class Codec {

        private Codec() {}

        public static String encodeItems(List<ItemStack> items) {
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                 BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
                out.writeInt(items.size());
                for (ItemStack item : items) out.writeObject(item);
                out.flush();
                return Base64.getEncoder().encodeToString(bytes.toByteArray());
            } catch (Exception e) {
                return "";
            }
        }

        public static List<ItemStack> decodeItems(String blob) {
            List<ItemStack> out = new ArrayList<>();
            if (blob == null || blob.isBlank()) return out;
            try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(blob));
                 BukkitObjectInputStream in = new BukkitObjectInputStream(bytes)) {
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    Object o = in.readObject();
                    if (o instanceof ItemStack stack) out.add(stack);
                }
            } catch (Exception ignored) {
                // a corrupt blob yields an empty store rather than an exception
            }
            return out;
        }

        public static String encodeTrusted(Set<UUID> trusted) {
            StringBuilder sb = new StringBuilder();
            for (UUID id : trusted) {
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
            return sb.toString();
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
