package dev.yanianz.bravenspawner.econ.currency;

import dev.yanianz.bravenspawner.econ.CurrencyFormat;
import dev.yanianz.bravenspawner.econ.CurrencyFormats;
import dev.yanianz.bravenspawner.econ.CurrencyMath;
import dev.yanianz.bravenspawner.econ.ExpMath;
import org.bukkit.entity.Player;

/** Built-in experience currencies: whole levels or raw experience points. */
public final class ExpCurrency extends OnlineCurrency {

    public static final String LEVELS = "EXP_LEVELS";
    public static final String POINTS = "EXP_POINTS";

    private final boolean levels;

    private ExpCurrency(boolean levels, CurrencyFormats formats) {
        super(levels ? LEVELS : POINTS, levels ? "Levels" : "Experience",
                CurrencyFormat.of("", levels ? " levels" : " XP", 0), formats);
        this.levels = levels;
    }

    public static ExpCurrency levels(CurrencyFormats formats) {
        return new ExpCurrency(true, formats);
    }

    public static ExpCurrency points(CurrencyFormats formats) {
        return new ExpCurrency(false, formats);
    }

    @Override
    double balanceOf(Player player) {
        return levels ? player.getLevel() : ExpMath.points(player.getLevel(), player.getExp());
    }

    @Override
    Result takeFrom(Player player, double amount) {
        int units = CurrencyMath.toWholeUnits(Math.ceil(amount - 1e-9));
        if (levels) {
            if (player.getLevel() < units) return Result.fail("not enough levels");
            player.setLevel(player.getLevel() - units);
            return Result.ok(units);
        }
        long current = ExpMath.points(player.getLevel(), player.getExp());
        if (current < units) return Result.fail("not enough experience");
        apply(player, current - units);
        return Result.ok(units);
    }

    @Override
    Result giveTo(Player player, double amount) {
        int units = CurrencyMath.toWholeUnits(amount);
        if (units <= 0) return Result.fail("amount is below one unit");
        if (levels) {
            long next = (long) player.getLevel() + units;
            if (next > ExpMath.MAX_LEVEL) return Result.fail("level limit reached");
            player.setLevel((int) next);
            return Result.ok(units);
        }
        long next = ExpMath.points(player.getLevel(), player.getExp()) + units;
        if (next > Integer.MAX_VALUE) return Result.fail("experience limit reached");
        apply(player, next);
        return Result.ok(units);
    }

    private static void apply(Player player, long points) {
        ExpMath.State state = ExpMath.fromPoints(points);
        player.setLevel(state.level());
        player.setExp(state.progress());
        player.setTotalExperience((int) Math.min(Integer.MAX_VALUE, points));
    }
}
