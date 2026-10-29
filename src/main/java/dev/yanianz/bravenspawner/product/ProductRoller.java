package dev.yanianz.bravenspawner.product;

import java.util.Objects;
import java.util.Random;

/** Bounded aggregate sampling; batches above 64 use a moment-matched normal approximation. */
public final class ProductRoller {
    private ProductRoller() {}

    public static int amount(ProductDefinition.Drop drop, int cycles, Random random) {
        Objects.requireNonNull(drop);
        Objects.requireNonNull(random);
        if (cycles <= 0) return 0;
        if (cycles <= 64) {
            long sum = 0;
            for (int i = 0; i < cycles; i++) sum += drop.roll(random);
            return bounded(sum);
        }
        double meanAmount = (drop.minAmount() + (double) drop.maxAmount()) / 2.0;
        double width = drop.maxAmount() - (double) drop.minAmount();
        double varianceAmount = width * (width + 2.0) / 12.0;
        double p = drop.chance();
        double mean = cycles * p * meanAmount;
        double variance = cycles * (p * varianceAmount + p * (1.0 - p) * meanAmount * meanAmount);
        // Sparse successes use geometric gaps to avoid the normal approximation near zero.
        if (cycles * p < 64 && p < 1) {
            long sum = 0;
            long position = -1;
            double logFailure = Math.log1p(-p);
            while (true) {
                double gap = Math.floor(Math.log1p(-random.nextDouble()) / logFailure);
                if (gap >= cycles) break;
                position += 1 + (long) gap;
                if (position >= cycles) break;
                sum += drop.minAmount() + random.nextInt(drop.maxAmount() - drop.minAmount() + 1);
            }
            return bounded(sum);
        }
        long sampled = Math.round(mean + random.nextGaussian() * Math.sqrt(variance));
        return bounded(Math.max(0, Math.min((long) cycles * drop.maxAmount(), sampled)));
    }

    public static int range(int min, int max, int cycles, Random random) {
        if (cycles <= 0 || max <= 0) return 0;
        if (min < 0 || max < min) throw new IllegalArgumentException("invalid production range");
        if (min == max) return bounded((long) min * cycles);
        double width = max - (double) min;
        if (cycles <= 64) {
            long sum = 0;
            for (int i = 0; i < cycles; i++) sum += random.nextLong(min, (long) max + 1);
            return bounded(sum);
        }
        double mean = cycles * (min + (double) max) / 2.0;
        double variance = cycles * width * (width + 2.0) / 12.0;
        long sampled = Math.round(mean + random.nextGaussian() * Math.sqrt(variance));
        return bounded(Math.max((long) cycles * min, Math.min((long) cycles * max, sampled)));
    }

    private static int bounded(long value) { return (int) Math.min(Integer.MAX_VALUE, value); }
}
