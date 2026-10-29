package dev.yanianz.bravenspawner.menu;

/** Bound a purchase before either economy or spawner state changes. */
public final class PurchaseMath {
    private PurchaseMath() { }

    public static int quantity(int requested, int stackRoom, int chunkRoom, double balance, double price) {
        if (requested <= 0 || stackRoom <= 0 || chunkRoom <= 0
                || !Double.isFinite(balance) || balance < 0
                || !Double.isFinite(price) || price <= 0) return 0;
        double affordable = Math.floor(balance / price);
        int budget = affordable >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) affordable;
        return Math.max(0, Math.min(Math.min(requested, budget), Math.min(stackRoom, chunkRoom)));
    }
}
