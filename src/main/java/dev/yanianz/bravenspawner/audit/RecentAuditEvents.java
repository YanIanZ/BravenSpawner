package dev.yanianz.bravenspawner.audit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Bounded session history. Callers serialize access; no Bukkit or disk access. */
final class RecentAuditEvents {
    private final int spawnerLimit;
    private final int eventLimit;
    // Access order is updated by writes only; repeatedly viewing a menu cannot
    // keep an inactive spawner ahead of a spawner receiving new events.
    private final LinkedHashMap<UUID, ArrayDeque<AuditEvent>> histories = new LinkedHashMap<>();

    RecentAuditEvents(int spawnerLimit, int eventLimit) {
        if (spawnerLimit <= 0 || eventLimit <= 0) throw new IllegalArgumentException("positive limits required");
        this.spawnerLimit = spawnerLimit;
        this.eventLimit = eventLimit;
    }

    void record(AuditEvent event) {
        ArrayDeque<AuditEvent> events = histories.remove(event.spawnerId());
        if (events == null) events = new ArrayDeque<>();
        if (events.size() == eventLimit) events.removeLast();
        events.addFirst(event);
        histories.put(event.spawnerId(), events);
        if (histories.size() > spawnerLimit) histories.remove(histories.keySet().iterator().next());
    }

    List<AuditEvent> recent(UUID spawnerId, int limit) {
        if (spawnerId == null || limit <= 0) return List.of();
        ArrayDeque<AuditEvent> events = histories.get(spawnerId);
        if (events == null) return List.of();
        List<AuditEvent> result = new ArrayList<>(Math.min(limit, events.size()));
        for (AuditEvent event : events) {
            if (result.size() == limit) break;
            result.add(event);
        }
        return List.copyOf(result);
    }

    void clear() {
        histories.clear();
    }
}
