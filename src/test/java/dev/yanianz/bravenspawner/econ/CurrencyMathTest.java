package dev.yanianz.bravenspawner.econ;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CurrencyMathTest {

    @Test void decimalCurrenciesRoundHalfUp() {
        assertEquals(1.34, CurrencyMath.payout(1.335, 2, false));
        assertEquals(2.0, CurrencyMath.payout(1.5, 0, false));
    }

    @Test void wholeUnitCurrenciesRoundPayoutsDown() {
        assertEquals(1.0, CurrencyMath.payout(1.99, 2, true));
        assertEquals(0.0, CurrencyMath.payout(0.6, 2, true));
        assertEquals(5.0, CurrencyMath.payout(4.999999999999, 2, true));
    }

    @Test void wholeUnitChargesRoundUpSoNothingBecomesFree() {
        assertEquals(1.0, CurrencyMath.charge(0.2, true));
        assertEquals(3.0, CurrencyMath.charge(3.0, true));
        assertEquals(2.5, CurrencyMath.charge(2.5, false));
        assertEquals(0.0, CurrencyMath.charge(0.0, true));
    }

    @Test void wholeUnitConversionRejectsOverflow() {
        assertEquals(7, CurrencyMath.toWholeUnits(7.9));
        assertThrows(IllegalArgumentException.class, () -> CurrencyMath.toWholeUnits(3e10));
        assertThrows(IllegalArgumentException.class, () -> CurrencyMath.toWholeUnits(Double.NaN));
    }

    @Test void formatWrapsCompactedNumberWithSymbolAndSuffix() {
        var style = new CurrencyFormat.MoneyStyle(true, true, List.of("%number%K", "%number%M"));
        assertEquals("12 points", CurrencyFormat.of("", " points", 0).format(12.0, style));
        assertEquals("1.50K points", CurrencyFormat.of("", " points", 0).format(1500, style));
        assertEquals("$999.50", CurrencyFormat.of("$", "", 2).format(999.5, style));
        assertEquals("999.50", CurrencyFormat.of("", "", -1).format(999.5, style));
        assertEquals("points", CurrencyFormat.of("", " points", 0).shortSymbol());
        assertEquals("$", CurrencyFormat.of("$", " dollars", 2).shortSymbol());
    }
}
