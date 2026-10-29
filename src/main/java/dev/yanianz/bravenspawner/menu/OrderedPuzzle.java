package dev.yanianz.bravenspawner.menu;

import java.util.List;

/** Per-window progress through a configured path of inventory cells. */
public final class OrderedPuzzle {
    public enum Result { IGNORED, CORRECT, RESET, COMPLETE }
    private final List<Integer> slots;
    private int index;

    public OrderedPuzzle(List<Integer> slots) { this.slots = List.copyOf(slots); }
    public Result click(int slot) {
        int clicked = slots.indexOf(slot);
        if (clicked < 0 || clicked < index || index >= slots.size()) return Result.IGNORED;
        if (clicked > index) {
            index = 0;
            return Result.RESET;
        }
        index++;
        return index == slots.size() ? Result.COMPLETE : Result.CORRECT;
    }
}
