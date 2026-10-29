package dev.yanianz.bravenspawner.menu;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OrderedPuzzleTest {
    @Test void onlyTheNextCellAdvancesAndEarlierCellsAreIgnored() {
        OrderedPuzzle puzzle = new OrderedPuzzle(List.of(0, 9, 18));
        assertEquals(OrderedPuzzle.Result.IGNORED, puzzle.click(50));
        assertEquals(OrderedPuzzle.Result.CORRECT, puzzle.click(0));
        assertEquals(OrderedPuzzle.Result.IGNORED, puzzle.click(0));
        assertEquals(OrderedPuzzle.Result.RESET, puzzle.click(18));
        assertEquals(OrderedPuzzle.Result.CORRECT, puzzle.click(0));
        assertEquals(OrderedPuzzle.Result.CORRECT, puzzle.click(9));
        assertEquals(OrderedPuzzle.Result.COMPLETE, puzzle.click(18));
        assertEquals(OrderedPuzzle.Result.IGNORED, puzzle.click(18));
    }
}
