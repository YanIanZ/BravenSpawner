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
    @Test void combinedMaterialsNeverExceedTheTotalCap() {
        Spawner model = model();
        Material stone = mock(Material.class), diamond = mock(Material.class);
        StockLimits limits = StockLimits.total(100);
        assertEquals(80, model.deposit(item(stone, 1), 80, limits));
        assertEquals(20, model.deposit(item(diamond, 1), 40, limits));
        assertEquals(100, model.storedCount());
        assertEquals(0, model.deposit(item(stone, 1), 1, limits));
    }

    /** VSS-01: one entry holds an exact count far beyond int, with no per-stack objects. */
    @Test void exactQuantitiesBeyondIntUseOneEntry() {
        Spawner model = model();
        Material rod = mock(Material.class);
        long big = 10_000_000_000L;
        assertEquals(big, model.deposit(item(rod, 1), big, StockLimits.UNLIMITED));
        assertEquals(big, model.deposit(item(rod, 1), big, StockLimits.UNLIMITED));
        assertEquals(2 * big, model.storedCount());
        assertEquals(1, model.distinctSlots());
        assertEquals(2 * big, model.stock().getFirst().quantity());
        assertEquals(64, model.drainSlotIfSimilar(0, item(rod, 1), 64).getAmount());
        assertEquals(2 * big - 64, model.quantityOf(item(rod, 1)));
    }

    /** VSS-08: checked arithmetic never wraps past Long.MAX_VALUE. */
    @Test void depositsNeverOverflowLong() {
        Spawner model = model();
        Material a = mock(Material.class), b = mock(Material.class);
        assertEquals(Long.MAX_VALUE - 5, model.deposit(item(a, 1), Long.MAX_VALUE - 5, StockLimits.UNLIMITED));
        assertEquals(5, model.deposit(item(b, 1), 50, StockLimits.UNLIMITED));
        assertEquals(Long.MAX_VALUE, model.storedCount());
        assertEquals(0, model.deposit(item(a, 1), 1, StockLimits.UNLIMITED));
        assertEquals(0, model.restore(item(b, 1), 1));
    }

    /** VSS-03: distinct and per-item caps are independent of the total cap. */
    @Test void distinctAndPerItemCapsApply() {
        Spawner model = model();
        Material a = mock(Material.class), b = mock(Material.class), c = mock(Material.class);
        StockLimits limits = new StockLimits(0, 2, 1_000);
        assertEquals(1_000, model.deposit(item(a, 1), 5_000, limits));
        assertEquals(10, model.deposit(item(b, 1), 10, limits));
        assertEquals(0, model.deposit(item(c, 1), 10, limits), "third identity refused");
        assertEquals(0, model.deposit(item(a, 1), 1, limits), "per-item cap reached");
        assertEquals(990, model.deposit(item(b, 1), 5_000, limits));
        assertEquals(2, model.distinctSlots());
    }

    /** The codec cannot persist more than 4096 identities, so no configuration may exceed it. */
    @Test void hardDistinctBoundAppliesEvenWhenUnlimited() {
        assertEquals(StockLimits.HARD_MAX_DISTINCT, StockLimits.UNLIMITED.effectiveDistinct());
        assertEquals(StockLimits.HARD_MAX_DISTINCT, new StockLimits(0, 1_000_000, 0).effectiveDistinct());
        assertEquals(7, new StockLimits(0, 7, 0).effectiveDistinct());
        assertEquals(4096, StockLimits.HARD_MAX_DISTINCT, "must match ItemCodec.MAX_ITEMS");
    }

    /** Restoring undelivered stock ignores gameplay caps: conservation over capacity. */
    @Test void restoreIgnoresCapsAndTakeRemovesByIdentity() {
        Spawner model = model();
        Material a = mock(Material.class), b = mock(Material.class);
        StockLimits limits = StockLimits.total(10);
        assertEquals(10, model.deposit(item(a, 1), 10, limits));
        assertEquals(5, model.restore(item(b, 1), 5));
        assertEquals(15, model.storedCount());
        assertEquals(3, model.take(item(b, 1), 3));
        assertEquals(2, model.take(item(b, 1), 99));
        assertEquals(0, model.take(item(b, 1), 1));
        assertEquals(1, model.distinctSlots());
        assertEquals(10, model.quantityOf(item(a, 1)));
    }

    /** VSS-09: legacy multi-slot stores merge by identity; a sum beyond long is refused. */
    @Test void replaceStockMergesIdentitiesAndRefusesOverflow() {
        Spawner model = model();
        Material a = mock(Material.class), b = mock(Material.class);
        model.replaceStock(java.util.List.of(new StockEntry(item(a, 1), 64), new StockEntry(item(b, 1), 7),
                new StockEntry(item(a, 1), 36)));
        assertEquals(2, model.distinctSlots());
        assertEquals(100, model.quantityOf(item(a, 1)));
        assertThrows(ArithmeticException.class, () -> model.replaceStock(java.util.List.of(
                new StockEntry(item(a, 1), Long.MAX_VALUE), new StockEntry(item(b, 1), 1))));
        assertEquals(107, model.storedCount(), "a refused load leaves the previous store intact");
    }

    @Test void drainSlotIfSimilarRejectsAStaleIdentity() {
        Spawner model = model();
        Material a = mock(Material.class), b = mock(Material.class);
        model.deposit(item(a, 1), 100, StockLimits.UNLIMITED);
        assertNull(model.drainSlotIfSimilar(0, item(b, 1), 10));
        assertEquals(100, model.storedCount());
    }

    /** VSS-05: escrow moves units out of storage atomically; release drops, refund returns them. */
    @Test void escrowHoldReleaseAndRefundConserveUnits() {
        Spawner model = model();
        Material a = mock(Material.class), b = mock(Material.class);
        model.deposit(item(a, 1), 100, StockLimits.UNLIMITED);
        model.deposit(item(b, 1), 10, StockLimits.UNLIMITED);
        UUID sale = UUID.randomUUID();
        Escrow held = model.hold(sale, UUID.randomUUID(), "VAULT", 9.5,
                java.util.List.of(new StockEntry(item(a, 1), 60), new StockEntry(item(b, 1), 50)));
        assertNotNull(held);
        assertEquals(70, held.quantity(), "a shortfall holds what is there");
        assertEquals(40, model.storedCount());
        assertEquals(1, model.escrows().size());
        assertEquals(1, model.store().stock().size(), "an identity held completely leaves storage");

        assertTrue(model.refund(sale));
        assertEquals(110, model.storedCount());
        assertTrue(model.escrows().isEmpty());
        assertFalse(model.refund(sale), "a refund happens once");

        UUID paid = UUID.randomUUID();
        model.hold(paid, UUID.randomUUID(), "VAULT", 1.0, java.util.List.of(new StockEntry(item(a, 1), 100)));
        assertTrue(model.release(paid));
        assertEquals(10, model.storedCount());
        assertFalse(model.refund(paid), "a released escrow cannot be refunded");
    }

    @Test void escrowCapAndEmptyHoldChangeNothing() {
        Spawner model = model();
        Material a = mock(Material.class), b = mock(Material.class);
        model.deposit(item(a, 1), 1_000, StockLimits.UNLIMITED);
        assertNull(model.hold(UUID.randomUUID(), UUID.randomUUID(), "V", 1.0,
                java.util.List.of(new StockEntry(item(b, 1), 5))));
        for (int i = 0; i < Escrow.MAX_PER_SPAWNER; i++) {
            assertNotNull(model.hold(UUID.randomUUID(), UUID.randomUUID(), "V", 1.0,
                    java.util.List.of(new StockEntry(item(a, 1), 1))));
        }
        assertNull(model.hold(UUID.randomUUID(), UUID.randomUUID(), "V", 1.0,
                java.util.List.of(new StockEntry(item(a, 1), 1))));
        assertEquals(1_000 - Escrow.MAX_PER_SPAWNER, model.storedCount());
    }

    @Test void replaceStoreRestoresEscrowsWithStock() {
        Spawner model = model();
        Material a = mock(Material.class);
        Escrow escrow = new Escrow(UUID.randomUUID(), UUID.randomUUID(), "V", 2.0, 5L,
                java.util.List.of(new StockEntry(item(a, 1), 7)));
        model.replaceStore(new StoreSnapshot(java.util.List.of(new StockEntry(item(a, 1), 3)), java.util.List.of(escrow)));
        assertEquals(3, model.storedCount());
        assertEquals(java.util.List.of(escrow), model.escrows());
        assertTrue(model.refund(escrow.id()));
        assertEquals(10, model.storedCount());
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
