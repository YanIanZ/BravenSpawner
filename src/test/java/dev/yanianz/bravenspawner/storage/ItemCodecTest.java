package dev.yanianz.bravenspawner.storage;

import org.bukkit.Material;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.SerializableAs;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class ItemCodecTest {

    private static ItemStack stack(int amount, byte[] template) {
        ItemStack item = mock(ItemStack.class);
        AtomicInteger value = new AtomicInteger(amount);
        when(item.getAmount()).thenAnswer(call -> value.get());
        doAnswer(call -> { value.set(call.getArgument(0)); return null; }).when(item).setAmount(anyInt());
        when(item.getType()).thenReturn(Material.ENDER_PEARL);
        when(item.clone()).thenAnswer(call -> stack(value.get(), template));
        when(item.serializeAsBytes()).thenAnswer(call -> {
            if (value.get() > 99) throw new IllegalStateException("Value must be within range [1;99]");
            return template.clone();
        });
        return item;
    }

    private static byte[] header(int version, int count) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(ItemCodec.MAGIC);
        out.writeInt(version);
        out.writeInt(count);
        return bytes.toByteArray();
    }

    @Test void v2RoundTripKeepsExactLogicalAmounts() throws Exception {
        List<ItemCodec.Entry> entries = List.of(
                new ItemCodec.Entry(500, new byte[] {1, 2, 3}),
                new ItemCodec.Entry(1, new byte[] {9}),
                new ItemCodec.Entry(Integer.MAX_VALUE, new byte[] {4, 5}));
        byte[] raw = ItemCodec.write(entries);
        assertTrue(ItemCodec.isV2(raw));
        assertEquals(entries, ItemCodec.read(raw));
        assertEquals(List.of(), ItemCodec.read(ItemCodec.write(List.of())));
    }

    @Test void legacyObjectStreamsAreNotMistakenForV2() {
        assertFalse(ItemCodec.isV2(new byte[] {(byte) 0xAC, (byte) 0xED, 0, 5, 0x77}));
        assertFalse(ItemCodec.isV2(new byte[] {'B', 'S'}));
        assertFalse(ItemCodec.isV2(null));
    }

    @Test void malformedV2BlobsFailClosed() throws Exception {
        assertThrows(IOException.class, () -> ItemCodec.read(header(3, 0)));
        assertThrows(IOException.class, () -> ItemCodec.read(header(2, ItemCodec.MAX_ITEMS + 1)));
        assertThrows(IOException.class, () -> ItemCodec.read(header(2, -1)));
        assertThrows(IOException.class, () -> ItemCodec.read(header(2, 1)), "truncated entry");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(header(2, 1));
        out.writeLong(5);
        out.writeInt(ItemCodec.MAX_TEMPLATE_BYTES + 1);
        assertThrows(IOException.class, () -> ItemCodec.read(bytes.toByteArray()), "oversized template");

        bytes.reset();
        out.write(header(2, 1));
        out.writeLong(0);
        out.writeInt(1);
        out.write(7);
        assertThrows(IOException.class, () -> ItemCodec.read(bytes.toByteArray()), "zero amount");

        byte[] valid = ItemCodec.write(List.of(new ItemCodec.Entry(3, new byte[] {1})));
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IOException.class, () -> ItemCodec.read(trailing));
        assertThrows(IllegalArgumentException.class,
                () -> ItemCodec.write(List.of(new ItemCodec.Entry(0, new byte[] {1}))));
    }

    @Test void amountsClampToIntRange() {
        assertEquals(Integer.MAX_VALUE, ItemCodec.toIntAmount(Long.MAX_VALUE));
        assertEquals(500, ItemCodec.toIntAmount(500));
        assertEquals(1, ItemCodec.toIntAmount(0));
    }

    @Test void encodeWritesAOneItemTemplateAndTheStoredAmount() throws Exception {
        byte[] template = {10, 20, 30};
        byte[] raw = ItemCodec.encode(List.of(stack(500, template)));
        List<ItemCodec.Entry> entries = ItemCodec.read(raw);
        assertEquals(List.of(new ItemCodec.Entry(500, template)), entries);
    }

    @Test void encodeFailureNamesSpawnerMaterialAndCause() {
        ItemStack broken = stack(500, new byte[] {1});
        ItemStack copy = stack(500, new byte[] {1});
        when(broken.clone()).thenReturn(copy);
        doThrow(new IllegalStateException("boom")).when(copy).serializeAsBytes();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Storage.Codec.encodeItems(List.of(broken), "spawner-42"));
        assertTrue(e.getMessage().contains("spawner-42"), e.getMessage());
        assertTrue(e.getMessage().contains("ENDER_PEARL"), e.getMessage());
        assertTrue(e.getMessage().contains("boom"), e.getMessage());
        String logged = Storage.describeFailure(new Storage.StorageException("checkpoint flush failed", e));
        assertTrue(logged.contains("spawner-42") && logged.contains("boom"), logged);
    }

    @Test void decodeRebuildsFromTemplatesAndReappliesAmount() throws Exception {
        byte[] raw = ItemCodec.write(List.of(new ItemCodec.Entry(1234, new byte[] {7, 7})));
        ItemStack rebuilt = stack(1, new byte[] {7, 7});
        try (var items = mockStatic(ItemStack.class)) {
            items.when(() -> ItemStack.deserializeBytes(any())).thenReturn(rebuilt);
            List<ItemStack> decoded = Storage.Codec.decodeItems(Base64.getEncoder().encodeToString(raw), "r");
            assertNotNull(decoded);
            assertEquals(1, decoded.size());
            assertEquals(1234, decoded.getFirst().getAmount());
        }
    }

    @Test void malformedBlobIsQuarantinedNotEmptied() {
        assertNull(Storage.Codec.decodeItems(Base64.getEncoder().encodeToString(new byte[] {'B', 'S', 'C', '2', 0}), "r"));
        assertNull(Storage.Codec.decodeItems("!!not base64!!", "r"));
        assertTrue(Storage.Codec.decodeItems("", "r").isEmpty());
    }

    @Test void legacyItemMapIsDeserializedAtOneThenGetsItsAmount() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("==", "org.bukkit.inventory.ItemStack");
        map.put("v", 3953);
        map.put("type", "ENDER_PEARL");
        map.put("amount", 500);
        List<Map<String, ?>> seen = new ArrayList<>();
        ItemStack built = stack(1, new byte[] {1});
        Object out = ItemCodec.resolveSerialized(map, m -> { seen.add(m); return built; });
        assertSame(built, out);
        assertFalse(seen.getFirst().containsKey("amount"), "Paper only sees an amount-1 item");
        assertEquals("ENDER_PEARL", seen.getFirst().get("type"));
        assertEquals(500, built.getAmount());
        assertEquals(500, map.get("amount"), "the source map is not mutated");
    }

    @Test void legacyAmountRules() throws Exception {
        assertEquals(1, ItemCodec.legacyAmount(Map.of("==", "org.bukkit.inventory.ItemStack")));
        assertEquals(64, ItemCodec.legacyAmount(Map.of("amount", 64)));
        assertThrows(IOException.class, () -> ItemCodec.legacyAmount(Map.of("amount", 0)));
        assertThrows(IOException.class, () -> ItemCodec.legacyAmount(Map.of("amount", "x")));
        assertTrue(ItemCodec.isItemMap(Map.of("==", "ItemStack")));
        assertFalse(ItemCodec.isItemMap(Map.of("==", "org.bukkit.util.Vector", "amount", 500)));
    }

    @Test void nonItemMapsPassThroughUntouched() throws Exception {
        Map<String, Object> meta = Map.of("==", "ItemMeta", "amount", 500);
        Object out = ItemCodec.resolveSerialized(meta, m -> m);
        assertEquals(meta, out);
    }

    /** Stands in for a 1.21.1 ItemStack: same alias, so the blob carries a real Bukkit Wrapper. */
    @SerializableAs("org.bukkit.inventory.ItemStack")
    public static final class LegacyItem implements ConfigurationSerializable {
        private final int amount;
        LegacyItem(int amount) { this.amount = amount; }
        @Override public Map<String, Object> serialize() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("v", 3953);
            map.put("type", "ENDER_PEARL");
            map.put("amount", amount);
            return map;
        }
    }

    @SuppressWarnings("deprecation")
    @Test void legacyBlobWithAmountAbove99Decodes() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeInt(2);
            out.writeObject(new LegacyItem(500));
            out.writeObject(new LegacyItem(7));
        }
        byte[] raw = bytes.toByteArray();
        assertFalse(ItemCodec.isV2(raw));
        List<Map<String, ?>> seen = new ArrayList<>();
        List<ItemStack> items = ItemCodec.decodeLegacy(raw, map -> {
            seen.add(map);
            if (map.containsKey("amount")) throw new IllegalStateException("Value must be within range [1;99]");
            return stack(1, new byte[] {1});
        });
        assertEquals(List.of(500, 7), items.stream().map(ItemStack::getAmount).toList());
        assertEquals(2, seen.size());
    }
}
