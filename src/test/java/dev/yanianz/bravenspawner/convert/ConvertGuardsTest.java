package dev.yanianz.bravenspawner.convert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Guard matrix: every conversion path evaluates the same fixed-order chain. */
class ConvertGuardsTest {

    @Test void allGuardsPassConverts() {
        assertEquals(ConvertCheck.CONVERTIBLE,
                ConvertGuards.checkBlock(true, true, false, false, true, true, true));
    }

    @Test void notReadyWinsOverEverything() {
        assertEquals(ConvertCheck.NOT_READY,
                ConvertGuards.checkBlock(false, false, true, true, false, false, false));
    }

    @Test void disabledBeatsWorldAndProduct() {
        assertEquals(ConvertCheck.DISABLED,
                ConvertGuards.checkBlock(true, false, true, true, false, false, false));
    }

    @Test void blacklistedBeatsIdentityAndCapacity() {
        assertEquals(ConvertCheck.BLACKLISTED,
                ConvertGuards.checkBlock(true, true, true, true, false, false, false));
    }

    @Test void alreadyManagedBeatsProductAndCapacity() {
        assertEquals(ConvertCheck.ALREADY_MANAGED,
                ConvertGuards.checkBlock(true, true, false, true, false, false, false));
    }

    @Test void productLessMobStaysVanilla() {
        assertEquals(ConvertCheck.NO_PRODUCT,
                ConvertGuards.checkBlock(true, true, false, false, false, true, true));
    }

    @Test void chunkCapRefuses() {
        assertEquals(ConvertCheck.CHUNK_CAP,
                ConvertGuards.checkBlock(true, true, false, false, true, false, true));
    }

    @Test void spentBudgetThrottlesLast() {
        assertEquals(ConvertCheck.THROTTLED,
                ConvertGuards.checkBlock(true, true, false, false, true, true, false));
    }
}
