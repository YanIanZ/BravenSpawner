package dev.yanianz.bravenspawner.boosters;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Thread-safe typed effects. No Bukkit access: producers only read immutable values. */
public final class BoosterState {
    /** Absolute epoch deadline; Long.MAX_VALUE denotes a permanent effect. */
    public record SavedEffect(Boosters.Type type, UUID subject, boolean player, double multiplier, long expiresAt) {
        public SavedEffect {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(subject, "subject");
            if (!Double.isFinite(multiplier) || (type == Boosters.Type.AUTOSELL ? multiplier < 0 : multiplier <= 0)
                    || expiresAt <= 0) throw new IllegalArgumentException("invalid saved booster");
        }
    }

    /** Carried personal effects are deliberately rebuilt from inventories. */
    public java.util.List<SavedEffect> snapshot() {
        long now = clock.getAsLong();
        return timed.entrySet().stream().filter(entry -> entry.getValue().expiresAt() > now)
                .map(entry -> new SavedEffect(entry.getKey().type(), entry.getKey().subject(),
                        entry.getKey().player(), entry.getValue().multiplier(), entry.getValue().expiresAt()))
                .toList();
    }

    public void restore(java.util.List<SavedEffect> saved) {
        long now = clock.getAsLong();
        for (SavedEffect effect : saved) {
            if (effect.expiresAt() > now) timed.put(new Key(effect.type(), effect.subject(), effect.player()),
                    new Active(effect.multiplier(), effect.expiresAt()));
        }
    }

    private record Key(Boosters.Type type, UUID subject, boolean player) { }
    private record Active(double multiplier, long expiresAt) { }
    private final Map<Key, Active> timed = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Boosters.Type, Double>> personal = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public BoosterState(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }

    public boolean grantPlayer(Boosters.Type type, UUID player, double multiplier, long seconds) {
        return grant(type, player, true, multiplier, seconds);
    }

    public boolean grantSpawner(Boosters.Type type, UUID spawner, double multiplier, long seconds) {
        return grant(type, spawner, false, multiplier, seconds);
    }

    public boolean grantLegacyPlayer(UUID player, double multiplier, long seconds) {
        return grantPlayer(Boosters.Type.LEGACY, player, multiplier, seconds);
    }

    /**
     * Applies a booster without ever weakening an active one of the same type and
     * subject. A stronger grant replaces the active effect; an equal grant keeps the
     * later deadline; a weaker grant is refused and returns false so the caller can
     * keep the item instead of consuming it for nothing.
     */
    private boolean grant(Boosters.Type type, UUID subject, boolean player, double multiplier, long seconds) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subject, "subject");
        if (!Double.isFinite(multiplier) || (type == Boosters.Type.AUTOSELL ? multiplier < 0 : multiplier <= 0)) {
            throw new IllegalArgumentException("invalid booster multiplier");
        }
        if (seconds != -1 && seconds <= 0) throw new IllegalArgumentException("duration must be positive or -1");
        long expiresAt;
        try { expiresAt = seconds == -1 ? Long.MAX_VALUE : Math.addExact(clock.getAsLong(), Math.multiplyExact(seconds, 1000L)); }
        catch (ArithmeticException exception) { throw new IllegalArgumentException("booster duration overflow", exception); }
        boolean[] applied = {true};
        Key key = new Key(type, subject, player);
        long now = clock.getAsLong();
        timed.compute(key, (k, current) -> {
            if (current == null || (current.expiresAt() != Long.MAX_VALUE && current.expiresAt() <= now)) {
                return new Active(multiplier, expiresAt);
            }
            if (multiplier > current.multiplier()) return new Active(multiplier, expiresAt);
            if (multiplier == current.multiplier()) {
                applied[0] = expiresAt > current.expiresAt();
                return applied[0] ? new Active(multiplier, expiresAt) : current;
            }
            applied[0] = false;
            return current;
        });
        return applied[0];
    }

    public double multiplier(Boosters.Type type, UUID owner, UUID spawner) {
        double value = Math.max(value(new Key(type, owner, true)), value(new Key(type, spawner, false)));
        if (type == Boosters.Type.XP || type == Boosters.Type.LOOT) {
            value = Math.max(value, Math.max(value(new Key(Boosters.Type.LEGACY, owner, true)),
                    value(new Key(Boosters.Type.LEGACY, spawner, false))));
        }
        return value;
    }

    private double value(Key key) {
        Active active = live(key);
        return active == null ? 1.0 : Math.max(1.0, active.multiplier());
    }

    private Active live(Key key) {
        if (key.subject() == null) return null;
        Active active = timed.get(key);
        if (active != null && active.expiresAt() != Long.MAX_VALUE && active.expiresAt() <= clock.getAsLong()) {
            timed.remove(key, active);
            return null;
        }
        return active;
    }

    public boolean active(Boosters.Type type, UUID owner, UUID spawner) {
        return live(new Key(type, owner, true)) != null || live(new Key(type, spawner, false)) != null;
    }

    /** Snapshot of carried items; XP/CASH applies at collection/payment, never production. */
    public void replacePersonal(UUID player, Map<Boosters.Type, Double> values) {
        Objects.requireNonNull(player, "player");
        Map<Boosters.Type, Double> validated = new java.util.EnumMap<>(Boosters.Type.class);
        values.forEach((type, multiplier) -> {
            if ((type != Boosters.Type.XP && type != Boosters.Type.CASH)
                    || !Double.isFinite(multiplier) || multiplier <= 0) throw new IllegalArgumentException("invalid personal effect");
            validated.put(type, multiplier);
        });
        if (validated.isEmpty()) personal.remove(player);
        else personal.put(player, Map.copyOf(validated));
    }

    public double personalMultiplier(Boosters.Type type, UUID player) {
        if (player == null) return 1.0;
        return personal.getOrDefault(player, Map.of()).getOrDefault(type, 1.0);
    }

    public long remainingSeconds(UUID player) {
        long remaining = 0;
        for (var entry : timed.entrySet()) {
            if (!entry.getKey().player() || !entry.getKey().subject().equals(player)) continue;
            Active active = live(entry.getKey());
            if (active == null) continue;
            if (active.expiresAt() == Long.MAX_VALUE) return -1;
            long millis = Math.max(0, active.expiresAt() - clock.getAsLong());
            remaining = Math.max(remaining, millis / 1000L);
        }
        return remaining;
    }

    public void sweep() {
        long now = clock.getAsLong();
        timed.entrySet().removeIf(entry -> entry.getValue().expiresAt() != Long.MAX_VALUE && entry.getValue().expiresAt() <= now);
    }

    public void clear() { timed.clear(); personal.clear(); }
}
