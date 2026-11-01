package dev.yanianz.bravenspawner.storage;

import org.bukkit.configuration.serialization.ConfigurationSerialization;
import dev.yanianz.bravenspawner.model.Escrow;
import dev.yanianz.bravenspawner.model.StockEntry;
import dev.yanianz.bravenspawner.model.StoreSnapshot;
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
import java.util.UUID;
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
 * <p>Format v3 is v2 followed by an escrow section ({@code int} count, then per
 * escrow: id and payee as two {@code long}s each, a UTF currency id, a {@code double}
 * value, a {@code long} creation time and its own v2-style entry list). It is written
 * only while a record holds escrows; otherwise the bytes are exactly v2, so older
 * releases keep reading ordinary records and quarantine (never truncate) the rare
 * record caught mid-sale.
 *
 * <p>Blobs without the magic are legacy Java-serialization streams written by
 * {@code BukkitObjectOutputStream}. Those may hold amounts above 99 (written on
 * 1.21.1), so the item map's amount is lifted out before Bukkit deserializes it
 * and re-applied afterwards.
 */
final class ItemCodec {

    static final byte[] MAGIC = {'B', 'S', 'C', '2'};
    static final int VERSION = 2;
    static final int VERSION_ESCROW = 3;
    /**
     * Entries one list may hold. Twice the model's distinct bound
     * ({@link dev.yanianz.bravenspawner.model.StockLimits#HARD_MAX_DISTINCT}): new
     * identities stop at the bound, and the headroom absorbs units returned to
     * storage by a racing withdrawal so a save can never fail on the count.
     */
    static final int MAX_ITEMS = 8192;
    static final int MAX_TEMPLATE_BYTES = 2 * 1024 * 1024;
    static final int MAX_ESCROWS = dev.yanianz.bravenspawner.model.Escrow.MAX_PER_SPAWNER;

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
        return write(entries, List.of());
    }

    /** One escrow on the wire; see the class comment for the v3 layout. */
    record RawEscrow(UUID id, UUID payee, String currency, double value, long createdAt, List<Entry> items) { }

    record RawStore(List<Entry> stock, List<RawEscrow> escrows) { }

    static byte[] write(List<Entry> entries, List<RawEscrow> escrows) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(MAGIC);
            out.writeInt(escrows.isEmpty() ? VERSION : VERSION_ESCROW);
            writeEntries(out, entries);
            if (!escrows.isEmpty()) {
                if (escrows.size() > MAX_ESCROWS) throw new IllegalArgumentException(escrows.size() + " escrows");
                out.writeInt(escrows.size());
                for (RawEscrow escrow : escrows) {
                    out.writeLong(escrow.id().getMostSignificantBits());
                    out.writeLong(escrow.id().getLeastSignificantBits());
                    out.writeLong(escrow.payee().getMostSignificantBits());
                    out.writeLong(escrow.payee().getLeastSignificantBits());
                    out.writeUTF(escrow.currency());
                    out.writeDouble(escrow.value());
                    out.writeLong(escrow.createdAt());
                    writeEntries(out, escrow.items());
                }
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return bytes.toByteArray();
    }

    private static void writeEntries(DataOutputStream out, List<Entry> entries) throws IOException {
        if (entries.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("inventory length " + entries.size() + " outside 0.." + MAX_ITEMS);
        }
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
    }

    /** Reads a v2 blob; a v3 blob (with escrows) is refused here, use {@link #readStore}. */
    static List<Entry> read(byte[] raw) throws IOException {
        RawStore store = readStore(raw);
        if (!store.escrows().isEmpty()) throw new IOException("blob carries escrows; read it as a store");
        return store.stock();
    }

    static RawStore readStore(byte[] raw) throws IOException {
        if (!isV2(raw)) throw new IOException("missing BSC2 header");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            in.skipNBytes(MAGIC.length);
            int version = in.readInt();
            if (version != VERSION && version != VERSION_ESCROW) {
                throw new IOException("unsupported item codec version " + version);
            }
            List<Entry> stock = readEntries(in, "entry");
            List<RawEscrow> escrows = new ArrayList<>();
            if (version == VERSION_ESCROW) {
                int count = in.readInt();
                if (count < 1 || count > MAX_ESCROWS) throw new IOException("escrow count " + count + " is invalid");
                for (int i = 0; i < count; i++) {
                    UUID id = new UUID(in.readLong(), in.readLong());
                    UUID payee = new UUID(in.readLong(), in.readLong());
                    String currency = in.readUTF();
                    double value = in.readDouble();
                    long createdAt = in.readLong();
                    if (!Double.isFinite(value) || value < 0) throw new IOException("escrow " + i + " value " + value);
                    List<Entry> items = readEntries(in, "escrow " + i + " entry");
                    if (items.isEmpty()) throw new IOException("escrow " + i + " holds no items");
                    escrows.add(new RawEscrow(id, payee, currency, value, createdAt, items));
                }
            }
            if (in.available() > 0) throw new IOException(in.available() + " trailing byte(s)");
            return new RawStore(stock, escrows);
        }
    }

    private static List<Entry> readEntries(DataInputStream in, String label) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_ITEMS) throw new IOException("inventory length " + n + " outside 0.." + MAX_ITEMS);
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long amount = in.readLong();
            if (amount < 1) throw new IOException(label + " " + i + " amount " + amount + " below 1");
            int length = in.readInt();
            if (length < 1 || length > MAX_TEMPLATE_BYTES || length > in.available()) {
                throw new IOException(label + " " + i + " template length " + length + " is invalid");
            }
            out.add(new Entry(amount, in.readNBytes(length)));
        }
        return out;
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

    /** Stock plus escrows; v2 bytes when there are no escrows. */
    static byte[] encodeStore(StoreSnapshot store) {
        List<Entry> stock = entries(store.stock());
        List<RawEscrow> escrows = new ArrayList<>(store.escrows().size());
        for (Escrow escrow : store.escrows()) {
            escrows.add(new RawEscrow(escrow.id(), escrow.payee(), escrow.currency(), escrow.value(),
                    escrow.createdAt(), entries(escrow.items())));
        }
        return write(stock, escrows);
    }

    static StoreSnapshot decodeStore(byte[] raw) throws IOException {
        RawStore store = readStore(raw);
        List<Escrow> escrows = new ArrayList<>(store.escrows().size());
        for (RawEscrow escrow : store.escrows()) {
            escrows.add(new Escrow(escrow.id(), escrow.payee(), escrow.currency(), escrow.value(),
                    escrow.createdAt(), stockOf(escrow.items())));
        }
        return new StoreSnapshot(stockOf(store.stock()), escrows);
    }

    private static List<Entry> entries(List<StockEntry> stock) {
        List<Entry> out = new ArrayList<>(stock.size());
        for (int i = 0; i < stock.size(); i++) {
            StockEntry entry = stock.get(i);
            if (entry == null) throw new IllegalArgumentException("entry #" + i + " is null");
            out.add(new Entry(entry.quantity(), template(entry.template(), i)));
        }
        return out;
    }

    private static List<StockEntry> stockOf(List<Entry> entries) throws IOException {
        List<StockEntry> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            ItemStack stack;
            try {
                stack = ItemStack.deserializeBytes(entry.template());
            } catch (RuntimeException e) {
                throw new IOException("entry " + i + " template could not be read: " + rootMessage(e), e);
            }
            if (stack == null || stack.getType().isAir()) throw new IOException("entry " + i + " template decoded to no item");
            out.add(new StockEntry(stack, entry.amount()));
        }
        return out;
    }

    /** Legacy object streams held int amounts; each stack becomes one entry. */
    static List<StockEntry> legacyStock(List<ItemStack> items) throws IOException {
        List<StockEntry> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (item == null || item.getType().isAir() || item.getAmount() < 1) {
                throw new IOException("legacy entry " + i + " is empty");
            }
            out.add(new StockEntry(item, item.getAmount()));
        }
        return out;
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
