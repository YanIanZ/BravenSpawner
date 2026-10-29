package dev.yanianz.bravenspawner.wand;

import java.util.Locale;

/** Persistent use budget or absolute expiry. Expiry is measured in epoch milliseconds. */
public record WandLifetime(int uses, long expiresAt) {
    public static final long DAY_MILLIS = 86_400_000L;
    public static final WandLifetime INFINITE = new WandLifetime(-1, 0L);

    public WandLifetime {
        if (uses < -1 || expiresAt < 0 || (uses >= 0 && expiresAt != 0)) {
            throw new IllegalArgumentException("invalid wand lifetime");
        }
    }

    public static WandLifetime parse(String duration, long now) {
        if (duration == null) return INFINITE;
        String text = duration.trim().toLowerCase(Locale.ROOT);
        if (text.equals("inf") || text.equals("infinite")) return INFINITE;
        try {
            if (text.endsWith("d")) {
                long days = Long.parseLong(text.substring(0, text.length() - 1));
                if (days <= 0) throw new IllegalArgumentException("days must be positive");
                return new WandLifetime(-1, Math.addExact(now, Math.multiplyExact(days, DAY_MILLIS)));
            }
            int uses = Integer.parseInt(text);
            if (uses <= 0) throw new IllegalArgumentException("uses must be positive");
            return new WandLifetime(uses, 0L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("duration is too large", exception);
        }
    }

    /** True while the budget allows another use; an expired day wand is not usable. */
    public boolean usable(long now) {
        return uses != 0 && (expiresAt == 0 || now < expiresAt);
    }

    /** Spends one use, leaving day and infinite wands unchanged. */
    public WandLifetime consume() {
        return uses > 0 ? new WandLifetime(uses - 1, 0) : this;
    }

    public String mode() { return uses >= 0 ? "uses" : expiresAt > 0 ? "days" : "infinite"; }
}
