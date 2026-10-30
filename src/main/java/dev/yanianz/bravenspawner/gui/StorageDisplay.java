package dev.yanianz.bravenspawner.gui;

import dev.yanianz.bravenspawner.util.Messages;

/** Pure display math for storage cells: a cell shows at most one vanilla stack but names the stored total. */
final class StorageDisplay {

    static final String AMOUNT_LORE_KEY = "gui.storage.amount-lore";

    private StorageDisplay() {}

    /** Visible stack count: the stored amount capped at the item's max stack size, never below 1. */
    static int visibleAmount(long stored, int maxStackSize) {
        int cap = Math.max(1, maxStackSize);
        return (int) Math.max(1L, Math.min(stored, cap));
    }

    /** The "Stored: 1,234" lore line, from messages.yml. */
    static String amountLore(Messages messages, long stored) {
        return messages.format(AMOUNT_LORE_KEY, "amount", MenuPlaceholders.number(Math.max(0L, stored)));
    }
}
