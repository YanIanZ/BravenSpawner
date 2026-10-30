package dev.yanianz.bravenspawner.listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player millisecond gate for one action ({@code cooldowns.break}, {@code cooldowns.stack}). */
final class ActionCooldowns {
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();

    /** Milliseconds still to wait, or 0 when the action may run (and is then recorded). */
    long acquire(UUID player, long cooldownMs, long now) {
        if (player == null || cooldownMs <= 0) return 0L;
        long[] wait = {0L};
        last.compute(player, (id, previous) -> {
            if (previous != null && now - previous < cooldownMs) {
                wait[0] = cooldownMs - (now - previous);
                return previous;
            }
            return now;
        });
        return wait[0];
    }

    void forget(UUID player) {
        if (player != null) last.remove(player);
    }
}
