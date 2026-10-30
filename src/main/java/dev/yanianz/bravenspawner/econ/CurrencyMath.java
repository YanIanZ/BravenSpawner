package dev.yanianz.bravenspawner.econ;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rounding rules shared by every currency; pure so it can be tested without a server. */
public final class CurrencyMath {

    private static final double EPSILON = 1e-9;

    private CurrencyMath() {}

    /**
     * Amount actually paid out: HALF_UP to {@code decimals}, then rounded down to
     * a whole unit for whole-unit currencies so a sale never pays a fraction of a
     * point, level or item.
     */
    public static double payout(double value, int decimals, boolean wholeUnits) {
        if (!Double.isFinite(value)) return value;
        double rounded = BigDecimal.valueOf(value)
                .setScale(Math.max(0, Math.min(6, decimals)), RoundingMode.HALF_UP).doubleValue();
        return wholeUnits ? Math.floor(rounded + EPSILON) : rounded;
    }

    /**
     * Amount actually charged. Whole-unit currencies round a fractional price up,
     * so a positive price can never be discounted to zero and become free.
     */
    public static double charge(double value, boolean wholeUnits) {
        if (!Double.isFinite(value) || value <= 0) return value;
        return wholeUnits ? Math.ceil(value - EPSILON) : value;
    }

    /** Whole units as an int, refusing values that do not fit. */
    public static int toWholeUnits(double value) {
        if (!Double.isFinite(value) || value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("amount out of range: " + value);
        }
        return (int) Math.floor(value + EPSILON);
    }
}
