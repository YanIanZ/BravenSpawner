package dev.yanianz.bravenspawner.audit;

import dev.yanianz.bravenspawner.model.Spawner;

import java.util.Objects;
import java.util.UUID;

/**
 * One audit record, immutable from creation to rendering.
 *
 * <p>Events are produced on region threads, entity threads and the main thread, then
 * consumed by the asynchronous writer and by the menu reader. A record is the only
 * shape that survives that trip without a lock: once constructed it cannot change,
 * so a queued event can never report a stack count that was mutated after the action
 * happened, and the writer may hold 50 000 of them without pinning live
 * {@link Spawner} references.
 *
 * <p>The compact constructor normalises every free-text field because the log file
 * is line-delimited: a player name or detail containing a newline would forge a
 * second record, a pipe would break the field parser, and an unbounded string would
 * let one event bloat the file. The caps are deliberately generous for humans and
 * hostile to machines.
 *
 * @param timestamp epoch milliseconds; non-positive values are replaced with
 *                  {@link System#currentTimeMillis()} so a broken clock cannot sort
 *                  an event before every real one
 * @param actor     the player or console UUID; null becomes {@link #SYSTEM_ACTOR}
 * @param actorName display name captured at action time, because a later rename
 *                  must not rewrite history
 * @param action    what happened; required — an event without a verb is not auditable
 * @param spawnerId the spawner the event belongs to; null becomes {@link #SYSTEM_ACTOR}
 * @param world     world name at action time; empty for non-spatial events
 * @param amount    whole units involved (stacks, items, currency); never negative
 * @param detail    free-form context, capped at {@value #MAX_DETAIL} characters
 */
public record AuditEvent(
        long timestamp,
        UUID actor,
        String actorName,
        Action action,
        UUID spawnerId,
        String world,
        int x,
        int y,
        int z,
        long amount,
        String detail) {

    /** Every auditable verb the plugin emits. */
    public enum Action { PLACE, BREAK, STACK, WITHDRAW, SELL, UPGRADE, ADMIN_GIVE, CORRUPT, REPAIR, RESTORE, ESCROW }

    /** Placeholder actor for console, corruption and repair events. */
    public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private static final int MAX_NAME = 64;
    private static final int MAX_WORLD = 64;
    private static final int MAX_DETAIL = 512;

    public AuditEvent {
        Objects.requireNonNull(action, "action");
        if (timestamp <= 0L) timestamp = System.currentTimeMillis();
        if (actor == null) actor = SYSTEM_ACTOR;
        actorName = text(actorName, "Console", MAX_NAME);
        if (spawnerId == null) spawnerId = SYSTEM_ACTOR;
        world = text(world, "", MAX_WORLD);
        amount = Math.max(0L, amount);
        detail = text(detail, "", MAX_DETAIL);
    }

    /**
     * Builds an event from a live spawner and stamps it with the current time.
     *
     * <p>The location is copied here, not read later: a spawner can be broken or
     * moved before the async writer drains the queue, and an audit line must keep
     * the coordinates the action happened at.
     */
    public static AuditEvent of(Spawner spawner, Action action, UUID actor,
                                String actorName, long amount, String detail) {
        long now = System.currentTimeMillis();
        if (spawner == null) {
            return new AuditEvent(now, actor, actorName, action,
                    SYSTEM_ACTOR, "", 0, 0, 0, amount, detail);
        }
        return new AuditEvent(now, actor, actorName, action, spawner.id(),
                spawner.world(), spawner.x(), spawner.y(), spawner.z(), amount, detail);
    }

    /** A readable location for menus: {@code world x, y, z}. */
    public String locationText() {
        return world.isEmpty() ? "-" : world + " " + x + ", " + y + ", " + z;
    }

    private static String text(String value, String fallback, int max) {
        if (value == null) return fallback;
        String out = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (out.isEmpty()) return fallback;
        return out.length() <= max ? out : out.substring(0, max);
    }
}
