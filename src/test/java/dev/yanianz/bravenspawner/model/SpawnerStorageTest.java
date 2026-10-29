package dev.yanianz.bravenspawner.model;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SpawnerStorageTest {
    private Spawner model() {
        return new Spawner(UUID.randomUUID(), UUID.randomUUID(), "world", 0, 0, 0,
                Spawner.Kind.MOB, "ZOMBIE", 1, 0, true);
    }
    private ItemStack item(Material type, int count) {
        AtomicInteger amount = new AtomicInteger(count);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(type);
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.getAmount()).thenAnswer(call -> amount.get());
        doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(item).setAmount(anyInt());
        when(item.clone()).thenAnswer(call -> item(type, amount.get()));
        when(item.isSimilar(any())).thenAnswer(call -> call.getArgument(0) instanceof ItemStack other && other.getType() == type);
        return item;
    }
    @Test void combinedMaterialsNeverExceedItemCapacity() {
        Spawner model = model();
        Material stone = mock(Material.class), diamond = mock(Material.class);
        assertNull(model.store(item(stone, 80), 45, 100));
        assertEquals(20, model.store(item(diamond, 40), 45, 100).getAmount());
        assertEquals(100, model.storedCount());
    }
    @Test void virtualQuantitiesUseOneSlotAndPreserveCountOnWithdrawal() {
        Spawner model = model();
        Material stone = mock(Material.class);
        assertNull(model.storeVirtual(item(stone, 1000000), 2000000));
        assertEquals(1000000, model.storedCount());
        assertEquals(1, model.distinctSlots());
        ItemStack expected = model.contents().getFirst();
        assertEquals(64, model.drainSlotIfSimilar(0, expected, 64).getAmount());
        assertEquals(999936, model.storedCount());
        assertEquals(1000000, model.storeVirtual(item(stone, 2000064), 2000000).getAmount());
        assertEquals(2000000, model.storedCount());
    }

    @Test void xpSaturatesWithoutOverflowAndNeverErasesExistingBalance() {
        Spawner model = model();
        model.addXp(Integer.MAX_VALUE);
        model.addXp(100);
        assertEquals(Integer.MAX_VALUE, model.storedXp());
        model.addXp(10, 100);
        assertEquals(Integer.MAX_VALUE, model.storedXp());
    }
}
