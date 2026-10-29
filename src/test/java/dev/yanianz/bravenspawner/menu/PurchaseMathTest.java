package dev.yanianz.bravenspawner.menu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PurchaseMathTest {
    @Test void clipsQuantityByBudgetStackRoomAndChunkHeadroom() {
        assertEquals(3, PurchaseMath.quantity(64, 5, 3, 100, 10));
        assertEquals(2, PurchaseMath.quantity(64, 5, 50, 29, 10));
        assertEquals(5, PurchaseMath.quantity(Integer.MAX_VALUE, 5, 50, 1000, 10));
    }
    @Test void rejectsInvalidEconomyAmountsAndFullSpawners() {
        assertEquals(0, PurchaseMath.quantity(1, 10, 10, 100, 0));
        assertEquals(0, PurchaseMath.quantity(1, 10, 10, Double.NaN, 1));
        assertEquals(0, PurchaseMath.quantity(1, 10, 10, 100, Double.POSITIVE_INFINITY));
        assertEquals(0, PurchaseMath.quantity(1, 0, 10, 100, 1));
    }
}
