package dev.yanianz.bravenspawner.econ;

/** Inventory arithmetic for the ITEM currency, kept free of Bukkit types for tests. */
public final class ItemCurrencyMath {

    private ItemCurrencyMath() {}

    /** Sum of matching amounts; non-matching slots are passed as 0. */
    public static long balance(int[] matching) {
        long total = 0;
        if (matching == null) return 0;
        for (int amount : matching) total += Math.max(0, amount);
        return total;
    }

    /**
     * Per-slot amounts to remove so exactly {@code amount} items leave, taking
     * from the first slots first. Null when the inventory holds too few.
     */
    public static int[] plan(int[] matching, int amount) {
        if (matching == null || amount < 0) return null;
        if (balance(matching) < amount) return null;
        int[] take = new int[matching.length];
        int left = amount;
        for (int i = 0; i < matching.length && left > 0; i++) {
            int here = Math.min(Math.max(0, matching[i]), left);
            take[i] = here;
            left -= here;
        }
        return take;
    }

    /** Stack sizes needed to hand out {@code amount} items. */
    public static int[] split(int amount, int maxStack) {
        if (amount <= 0) return new int[0];
        int stack = Math.max(1, maxStack);
        int full = amount / stack;
        int rest = amount % stack;
        int[] stacks = new int[full + (rest > 0 ? 1 : 0)];
        for (int i = 0; i < full; i++) stacks[i] = stack;
        if (rest > 0) stacks[full] = rest;
        return stacks;
    }
}
