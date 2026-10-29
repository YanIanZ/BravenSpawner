package dev.yanianz.bravenspawner.product;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;
class ProductRollerTest {
    @Test void deterministicDropsScaleWithoutPerStackLoop() {
        var drop = new ProductDefinition.Drop(Material.DIAMOND, 3, 3, 1.0);
        assertEquals(600000, ProductRoller.amount(drop, 200000, new Random(1)));
        assertEquals(0, ProductRoller.amount(drop, 0, new Random(1)));
    }
    @Test void variableDropsStayWithinBoundsAndPreserveMean() {
        var drop = new ProductDefinition.Drop(Material.IRON_INGOT, 1, 3, .5);
        int amount = ProductRoller.amount(drop, 100000, new Random(1));
        assertTrue(amount > 98000 && amount < 102000, "yield=" + amount);
        for (int seed=0; seed<100; seed++) {
            int single = ProductRoller.amount(drop, 1, new Random(seed));
            assertTrue(single >= 0 && single <= 3);
        }
    }
    @Test void hugeAmountsSaturateAndXpRangesRespectBounds() {
        var drop = new ProductDefinition.Drop(Material.DIAMOND, 6400, 6400, 1.0);
        assertEquals(Integer.MAX_VALUE, ProductRoller.amount(drop, Integer.MAX_VALUE, new Random(1)));
        assertEquals(25, ProductRoller.range(5, 5, 5, new Random(1)));
        assertEquals(0, ProductRoller.range(0, 0, 100, new Random(1)));
    }
}
