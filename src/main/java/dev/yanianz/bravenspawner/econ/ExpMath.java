package dev.yanianz.bravenspawner.econ;

/**
 * Vanilla experience curve (Java Edition 1.8+), used by the EXP currencies.
 *
 * <p>Bukkit's {@code getTotalExperience()} is not reliable (it is not lowered by
 * enchanting), so the balance is rebuilt from level and progress instead.
 */
public final class ExpMath {

    /** Highest level whose total still fits comfortably in an int point count. */
    public static final int MAX_LEVEL = 21863;

    private ExpMath() {}

    /** Level and fractional progress towards the next level. */
    public record State(int level, float progress) {}

    /** Points needed to go from {@code level} to {@code level + 1}. */
    public static int toNext(int level) {
        if (level < 0) return 0;
        if (level <= 15) return 2 * level + 7;
        if (level <= 30) return 5 * level - 38;
        return 9 * level - 158;
    }

    /** Points needed to reach {@code level} from zero. */
    public static long totalForLevel(int level) {
        if (level <= 0) return 0;
        long l = level;
        if (level <= 16) return l * l + 6 * l;
        if (level <= 31) return Math.round(2.5 * l * l - 40.5 * l + 360);
        return Math.round(4.5 * l * l - 162.5 * l + 2220);
    }

    /** Total points represented by a level and its progress bar. */
    public static long points(int level, float progress) {
        float clamped = Math.max(0F, Math.min(1F, Float.isFinite(progress) ? progress : 0F));
        return totalForLevel(Math.max(0, level)) + Math.round(clamped * toNext(Math.max(0, level)));
    }

    /** Level and progress for a total point count. */
    public static State fromPoints(long points) {
        if (points <= 0) return new State(0, 0F);
        int low = 0;
        int high = MAX_LEVEL;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (totalForLevel(mid) <= points) low = mid; else high = mid - 1;
        }
        int level = low;
        long remainder = points - totalForLevel(level);
        int next = toNext(level);
        float progress = next <= 0 ? 0F : Math.min(0.9999F, (float) remainder / next);
        return new State(level, progress);
    }
}
