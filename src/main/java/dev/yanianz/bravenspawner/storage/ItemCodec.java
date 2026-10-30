package dev.yanianz.bravenspawner.storage;

import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Binary codec for logical storage stacks.
 *
 * <p>Spawner storage keeps one entry per item kind with an amount far above the
 * vanilla stack limit (500 pearls is one entry). Paper 26.2 refuses to serialize
 * any stack above 99 through both {@code BukkitObjectOutputStream} and
 * {@code serializeAsBytes}, so the amount is stored separately from a one-item
 * template.
 *
 * <p>Format v2: {@code "BSC2"} magic, {@code int} version, {@code int} count, then
 * per entry a {@code long} amount, an {@code int} template length and the
 * template bytes ({@link ItemStack#serializeAsBytes()} of an amount-1 clone, which
 * carries the data version so Paper upgrades it on load).
 *
 * <p>Blobs without the magic are legacy Java-serialization streams written by
 * {@code BukkitObjectOutputStream}. Those may hold amounts above 99 (written on
 * 1.21.1), so the item map's amount is lifted out before Bukkit deserializes it
 * and re-applied afterwards.
 */
final class ItemCodec {

    static final byte[] MAGIC = {'B', 'S', 'C', '2'};
    static final int VERSION = 2;
    static final int MAX_ITEMS = 4096;
    static final int MAX_TEMPLATE_BYTES = 2 * 1024 * 1024;

    static final String TYPE_KEY = ConfigurationSerialization.SERIALIZED_TYPE_KEY;
    static final String AMOUNT_KEY = "amount";
    private static final String ITEM_CLASS = "org.bukkit.inventory.ItemStack";

    /** One stored stack: exact logical amount plus a one-item template. */
    record Entry(long amount, byte[] template) {
        @Override public boolean equals(Object o) {
            return o instanceof Entry e && e.amount == amount && Arrays.equals(e.template, template);
        }
        @Override public int hashCode() { return Long.hashCode(amount) * 31 + Arrays.hashCode(template); }
        @Override public String toString() { return "Entry[" + amount + ", " + template.length + "B]"; }
    }

    private ItemCodec() {}

    // ---- format v2 (pure bytes) ----

    static boolean isV2(byte[] raw) {
        if (raw == null || raw.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) if (raw[i] != MAGIC[i]) return false;
        return true;
    }

    static byte[] write(List<Entry> entries) {
        if (entries.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("inventory length " + entries.size() + " outside 0.." + MAX_ITEMS);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(entries.size());
            for (Entry entry : entries) {
                if (entry.amount() < 1) throw new IllegalArgumentException("amount " + entry.amount() + " below 1");
                if (entry.template().length == 0 || entry.template().length > MAX_TEMPLATE_BYTES) {
                    throw new IllegalArgumentException("template length " + entry.template().length
                            + " outside 1.." + MAX_TEMPLATE_BYTES);
                }
                out.writeLong(entry.amount());
                out.writeInt(entry.template().length);
                out.write(entry.template());
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return bytes.toByteArray();
    }

    static List<Entry> read(byte[] raw) throws IOException {
        if (!isV2(raw)) throw new IOException("missing BSC2 header");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            in.skipNBytes(MAGIC.length);
            int version = in.readInt();
            if (version != VERSION) throw new IOException("unsupported item codec version " + version);
            int n = in.readInt();
            if (n < 0 || n > MAX_ITEMS) throw new IOException("inventory length " + n + " outside 0.." + MAX_ITEMS);
            List<Entry> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long amount = in.readLong();
                if (amount < 1) throw new IOException("entry " + i + " amount " + amount + " below 1");
                int length = in.readInt();
                if (length < 1 || length > MAX_TEMPLATE_BYTES || length > in.available()) {
                    throw new IOException("entry " + i + " template length " + length + " is invalid");
                }
                out.add(new Entry(amount, in.readNBytes(length)));
            }
            if (in.available() > 0) throw new IOException(in.available() + " trailing byte(s)");
            return out;
        }
    }

    /** Logical amounts are ints in memory; anything wider is clamped, never wrapped. */
    static int toIntAmount(long amount) {
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, amount));
    }

    // ---- ItemStack adapters ----

    static byte[] encode(List<ItemStack> items) {
        List<Entry> entries = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (item == null) throw new IllegalArgumentException("item #" + i + " is null");
            entries.add(new Entry(item.getAmount(), template(item, i)));
        }
        return write(entries);
    }

    private static byte[] template(ItemStack item, int index) {
        try {
            ItemStack one = item.clone();
            one.setAmount(1);
            byte[] bytes = one.serializeAsBytes();
            if (bytes == null || bytes.length == 0) throw new IllegalStateException("empty template");
            return bytes;
        } catch (RuntimeException e) {
            throw new IllegalStateException("item #" + index + " " + describe(item) + " could not be serialized: "
                    + rootMessage(e), e);
        }
    }

    static List<ItemStack> decode(byte[] raw) throws IOException {
        List<Entry> entries = read(raw);
        List<ItemStack> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            ItemStack stack;
            try {
                stack = ItemStack.deserializeBytes(entry.template());
            } catch (RuntimeException e) {
                throw new IOException("entry " + i + " template could not be read: " + rootMessage(e), e);
            }
            if (stack == null) throw new IOException("entry " + i + " template decoded to null");
            stack.setAmount(toIntAmount(entry.amount()));
            out.add(stack);
        }
        return out;
    }

    // ---- legacy BukkitObjectInputStream blobs ----

    static List<ItemStack> decodeLegacy(byte[] raw) throws IOException, ClassNotFoundException {
        return decodeLegacy(raw, ConfigurationSerialization::deserializeObject);
    }

    static List<ItemStack> decodeLegacy(byte[] raw, Function<Map<String, ?>, Object> deserializer)
            throws IOException, ClassNotFoundException {
        try (LegacyInput in = new LegacyInput(new ByteArrayInputStream(raw), deserializer)) {
            int n = in.readInt();
            if (n < 0 || n > MAX_ITEMS) throw new IOException("inventory length " + n + " outside 0.." + MAX_ITEMS);
            List<ItemStack> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Object o = in.readObject();
                if (!(o instanceof ItemStack stack)) {
                    throw new IOException("unexpected inventory element " + (o == null ? "null" : o.getClass()));
                }
                out.add(stack);
            }
            return out;
        }
    }

    static boolean isItemMap(Map<String, ?> map) {
        Object type = map.get(TYPE_KEY);
        return ITEM_CLASS.equals(type) || "ItemStack".equals(type);
    }

    /** Amount recorded in a serialized item map; absent means 1 (Bukkit omits it). */
    static long legacyAmount(Map<String, ?> map) throws IOException {
        Object value = map.get(AMOUNT_KEY);
        if (value == null) return 1L;
        if (value instanceof Number number) {
            long amount = number.longValue();
            if (amount < 1) throw new IOException("legacy item amount " + amount + " below 1");
            return amount;
        }
        throw new IOException("legacy item amount is not a number: " + value);
    }

    /**
     * Rebuilds one serialized object. Item maps are deserialized at amount 1 so
     * Paper's stack-size check passes, then receive their recorded amount.
     */
    static Object resolveSerialized(Map<String, ?> raw, Function<Map<String, ?>, Object> deserializer)
            throws IOException {
        if (!isItemMap(raw)) return deserializer.apply(raw);
        long amount = legacyAmount(raw);
        Map<String, Object> single = new LinkedHashMap<>(raw);
        single.remove(AMOUNT_KEY);
        Object resolved;
        try {
            resolved = deserializer.apply(single);
        } catch (RuntimeException e) {
            throw new IOException("legacy item could not be deserialized: " + rootMessage(e), e);
        }
        if (resolved instanceof ItemStack stack) stack.setAmount(toIntAmount(amount));
        return resolved;
    }

    @SuppressWarnings("deprecation")
    private static final class LegacyInput extends BukkitObjectInputStream {

        private static final Field WRAPPER_MAP = wrapperMapField();

        private final Function<Map<String, ?>, Object> deserializer;

        LegacyInput(InputStream in, Function<Map<String, ?>, Object> deserializer) throws IOException {
            super(in);
            this.deserializer = deserializer;
        }

        @Override
        protected Object resolveObject(Object obj) throws IOException {
            if (WRAPPER_MAP == null || obj == null || obj.getClass() != WRAPPER_MAP.getDeclaringClass()) {
                return super.resolveObject(obj);
            }
            Map<String, ?> map;
            try {
                @SuppressWarnings("unchecked") Map<String, ?> cast = (Map<String, ?>) WRAPPER_MAP.get(obj);
                map = cast;
            } catch (ReflectiveOperationException | ClassCastException e) {
                return super.resolveObject(obj);
            }
            if (map == null) return super.resolveObject(obj);
            return resolveSerialized(map, deserializer);
        }

        private static Field wrapperMapField() {
            try {
                Field field = Class.forName("org.bukkit.util.io.Wrapper").getDeclaredField("map");
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }

    static String describe(ItemStack item) {
        try {
            return item.getType() + " x" + item.getAmount();
        } catch (RuntimeException e) {
            return "<unknown item>";
        }
    }

    static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
