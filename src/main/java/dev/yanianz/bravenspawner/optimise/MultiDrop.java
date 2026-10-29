package dev.yanianz.bravenspawner.optimise;

import dev.yanianz.bravenspawner.config.Settings;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Rolls a product's drop table for a batch of production cycles and aggregates
 * the result by material.
 *
 * <p>This is the {@code multiple-drop} behaviour: when a drop entry carries a
 * {@code chance}, every unit of its configured amount gets its own roll in every
 * cycle instead of one all-or-nothing roll for the whole cycle. A 30% drop of 3
 * items over 10 cycles therefore yields a count around 9, not either 0 or 30.
 * The aggregate uses the binomial distribution rather than a loop over every
 * unit, so a 200,000-credit spawner costs the same as a one-credit spawner.
 *
 * <p>Aggregation also keeps storage compact: drops that name the same material
 * are summed into one {@link ItemStack} per material, in first-appearance order.
 * The returned amounts are virtual-storage quantities and may exceed a vanilla
 * stack; {@code Spawner.store} splits them into stack-sized slots on insert.
 *
 * <p>The only Bukkit type used is {@link ItemStack}, so the calculation is free
 * of live server state and safe on any thread.
 */
public final class MultiDrop {

    /**
     * Below this expected success count the exact geometric sampler is cheaper;
     * above it the normal approximation keeps the cost independent of batch size.
     */
    private static final double EXACT_TRIAL_THRESHOLD = 64.0;

    /**
     * Rolls the whole product for {@code multiplier} cycles.
     *
     * @param product    parsed product whose {@code drops} are rolled; null yields empty
     * @param random     source of randomness, never null
     * @param multiplier number of production cycles (the spawner's credit count);
     *                   zero or negative yields empty
     * @return one aggregated stack per dropped material, empty when nothing dropped
     */
    public List<ItemStack> roll(Settings.Product product, Random random, int multiplier) {
        Objects.requireNonNull(random, "random");
        if (product == null || multiplier <= 0 || product.drops.isEmpty()) return List.of();

        Map<ItemStack, Long> totals = new LinkedHashMap<>();
        for (Settings.Drop drop : product.drops) {
            if (drop == null || drop.material == null) continue;
            // Enum#name avoids Material.isAir(), which initialises the server
            // registry and would make this pure calculation require a live host.
            if (drop.material.name().equals("AIR")) continue;

            double chance = drop.chance;
            if (!(chance > 0.0)) continue;

            int perCycle = Math.max(1, drop.amount);
            long trials = (long) perCycle * multiplier;
            if (trials <= 0) continue;

            long successes = chance >= 1.0
                    ? trials
                    : sampleBinomial(trials, Math.min(1.0, chance), random);
            if (successes <= 0) continue;

            totals.merge(new ItemStack(drop.material, 1), successes, MultiDrop::saturatingAdd);
        }

        if (totals.isEmpty()) return List.of();
        List<ItemStack> stacks = new ArrayList<>(totals.size());
        for (Map.Entry<ItemStack, Long> entry : totals.entrySet()) {
            int amount = (int) Math.min(Integer.MAX_VALUE, entry.getValue());
            if (amount > 0) stacks.add(new ItemStack(entry.getKey().getType(), amount));
        }
        return List.copyOf(stacks);
    }

    /**
     * Exact sampler for small expected counts and a normal approximation above
     * {@link #EXACT_TRIAL_THRESHOLD}, matching the production pass's cost model:
     * cost is proportional to successes below the threshold and constant above
     * it, never proportional to the raw trial count.
     */
    private static long sampleBinomial(long trials, double probability, Random random) {
        double mean = trials * probability;
        if (mean < EXACT_TRIAL_THRESHOLD) {
            double logFailure = Math.log1p(-probability);
            long successes = 0;
            long position = -1;
            while (true) {
                double u = random.nextDouble();
                double gap = Math.floor(Math.log(1.0 - u) / logFailure);
                position += 1 + (gap < 0 ? 0 : (long) gap);
                if (position >= trials) break;
                successes++;
            }
            return successes;
        }

        double deviation = Math.sqrt(trials * probability * (1.0 - probability));
        long sample = Math.round(mean + deviation * random.nextGaussian());
        if (sample < 0) return 0;
        return Math.min(sample, trials);
    }

    private static long saturatingAdd(long left, long right) {
        long sum = left + right;
        if (sum < 0 || sum > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return sum;
    }
}
