package dev.yanianz.bravenspawner.econ;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExpAndItemMathTest {

    @Test void vanillaLevelTotals() {
        assertEquals(0, ExpMath.totalForLevel(0));
        assertEquals(7, ExpMath.totalForLevel(1));
        assertEquals(352, ExpMath.totalForLevel(16));
        assertEquals(394, ExpMath.totalForLevel(17));
        assertEquals(1395, ExpMath.totalForLevel(30));
        assertEquals(1507, ExpMath.totalForLevel(31));
        assertEquals(1628, ExpMath.totalForLevel(32));
        for (int level = 0; level < 200; level++) {
            assertEquals(ExpMath.totalForLevel(level + 1), ExpMath.totalForLevel(level) + ExpMath.toNext(level),
                    "curve must be continuous at level " + level);
        }
    }

    @Test void pointsIncludeProgressBar() {
        assertEquals(352 + 21, ExpMath.points(16, 0.5F)); // toNext(16) = 42
        assertEquals(0, ExpMath.points(0, 0F));
        assertEquals(7, ExpMath.points(0, 1F));
    }

    @Test void pointsRoundTripThroughLevelAndProgress() {
        for (long points : new long[] {0, 1, 6, 7, 351, 352, 353, 1395, 1506, 1507, 1628, 50_000}) {
            ExpMath.State state = ExpMath.fromPoints(points);
            assertEquals(points, ExpMath.points(state.level(), state.progress()), "points " + points);
            assertTrue(state.progress() >= 0F && state.progress() < 1F);
        }
        assertEquals(new ExpMath.State(16, 0F), ExpMath.fromPoints(352));
    }

    @Test void itemBalanceCountsOnlyMatchingSlots() {
        assertEquals(70, ItemCurrencyMath.balance(new int[] {64, 0, 6}));
        assertEquals(0, ItemCurrencyMath.balance(null));
    }

    @Test void itemWithdrawalPlanTakesExactlyOrNothing() {
        assertArrayEquals(new int[] {64, 0, 6, 0}, ItemCurrencyMath.plan(new int[] {64, 0, 10, 5}, 70));
        assertArrayEquals(new int[] {0, 0}, ItemCurrencyMath.plan(new int[] {3, 4}, 0));
        assertNull(ItemCurrencyMath.plan(new int[] {3, 4}, 8));
    }

    @Test void itemDepositSplitsIntoStacks() {
        assertArrayEquals(new int[] {64, 64, 2}, ItemCurrencyMath.split(130, 64));
        assertArrayEquals(new int[] {16}, ItemCurrencyMath.split(16, 16));
        assertArrayEquals(new int[0], ItemCurrencyMath.split(0, 64));
    }
}
