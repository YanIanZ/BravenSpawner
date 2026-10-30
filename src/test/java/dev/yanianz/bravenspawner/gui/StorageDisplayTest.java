package dev.yanianz.bravenspawner.gui;

import dev.yanianz.bravenspawner.util.Messages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StorageDisplayTest {

    @Test void visibleCountIsCappedAtOneVanillaStack() {
        assertEquals(64, StorageDisplay.visibleAmount(500, 64));
        assertEquals(16, StorageDisplay.visibleAmount(1_234, 16));
        assertEquals(1, StorageDisplay.visibleAmount(3, 1));
        assertEquals(5, StorageDisplay.visibleAmount(5, 64));
        assertEquals(1, StorageDisplay.visibleAmount(0, 64));
        assertEquals(1, StorageDisplay.visibleAmount(500, 0));
        assertEquals(99, StorageDisplay.visibleAmount(Long.MAX_VALUE, 99));
    }

    @Test void loreShowsTheExactStoredTotalWithGrouping() {
        String line = StorageDisplay.amountLore(Messages.defaults(), 1_234_567);
        assertTrue(line.contains("1,234,567"), line);
        assertTrue(line.contains("Stored"), line);
    }
}
