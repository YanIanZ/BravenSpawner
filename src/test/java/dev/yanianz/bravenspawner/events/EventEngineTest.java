package dev.yanianz.bravenspawner.events;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EventEngineTest {
    private static final Instant NOW = Instant.parse("2026-12-05T20:00:00Z");
    private static EventDefinition definition(String id, double weight, double xp, double cash, long seconds) {
        return new EventDefinition(id, weight, xp, cash, seconds, Set.of(), null, null, 1, 12,
                List.of("{event_name} {duration} {xp_multiplier} {cash_multiplier}"), List.of("ended {event_name}"),
                List.of("say {event_name}"), List.of("say ended {event_name}"));
    }
    private static EventSettings settings(boolean enabled, double chance, boolean always, int max, EventDefinition... events) {
        return new EventSettings(enabled, 300, chance, always, max, false, List.of(), List.of(), List.of(events));
    }

    @Test void weightedChoiceStartsOnlyOneAndMultipliesUntilExactExpiry() {
        MutableClock clock = new MutableClock(NOW);
        var low = definition("low", 1, 2, 1, 10);
        var high = definition("high", 3, 4, 1.5, 10);
        EventEngine engine = new EventEngine(settings(true, 1, false, 1, low, high), clock, () -> .5);
        var changes = engine.tick();
        assertEquals(1, changes.size());
        assertEquals("high", changes.getFirst().definition().id());
        assertEquals(4, engine.xpMultiplier());
        assertEquals(1.5, engine.cashMultiplier());
        clock.now = NOW.plusSeconds(10);
        assertEquals(1, engine.xpMultiplier(), "expired events must not boost before the next scheduler tick");
        assertEquals(EventEngine.Phase.END, engine.tick().getFirst().phase());
        assertTrue(engine.activeEvents().isEmpty());
    }

    @Test void stackedEventsMultiplyAndAlwaysActiveRespectsConcurrency() {
        MutableClock clock = new MutableClock(NOW);
        Queue<Double> rolls = new ArrayDeque<>(List.of(0.0, .75, 0.0, .75));
        EventEngine engine = new EventEngine(settings(true, 0, true, 2,
                definition("xp", 1, 2, 1.5, 900), definition("cash", 1, 3, 2, 900)), clock, rolls::remove);
        engine.tick();
        clock.now = NOW.plusSeconds(300);
        engine.tick();
        assertEquals(6, engine.xpMultiplier());
        assertEquals(3, engine.cashMultiplier());
        clock.now = NOW.plusSeconds(600);
        assertTrue(engine.tick().isEmpty());
        assertEquals(2, engine.activeEvents().size());
    }

    @Test void alwaysActiveReplacesExpiredEventWithoutWaitingForCheckInterval() {
        MutableClock clock = new MutableClock(NOW);
        EventEngine engine = new EventEngine(settings(true, 0, true, 1, definition("xp", 1, 2, 1, 10)), clock, () -> 0);
        assertEquals(EventEngine.Phase.START, engine.tick().getFirst().phase());
        clock.now = NOW.plusSeconds(10);
        var changes = engine.tick();
        assertEquals(List.of(EventEngine.Phase.END, EventEngine.Phase.START), changes.stream().map(EventEngine.Transition::phase).toList());
        assertEquals(2, engine.xpMultiplier());
    }

    @Test void calendarInvalidWeightedPickDoesNotRerollAndDisabledConfigurationClearsBoosts() {
        MutableClock clock = new MutableClock(NOW);
        var invalid = new EventDefinition("summer", 1, 10, 10, 60, Set.of(), null, null, 6, 8,
                List.of(), List.of(), List.of(), List.of());
        var valid = definition("valid", 1, 2, 2, 60);
        EventEngine engine = new EventEngine(settings(true, 1, false, 1, invalid, valid), clock, () -> 0);
        assertTrue(engine.tick().isEmpty());
        assertEquals(1, engine.xpMultiplier());
        engine.configure(settings(true, 1, true, 1, valid));
        assertEquals(1, engine.tick().size());
        assertTrue(engine.configure(settings(false, 1, true, 1, valid)).isEmpty(), "reload/disable clears without executing end commands, matching the reference");
        assertEquals(1, engine.xpMultiplier());
        assertTrue(engine.tick().isEmpty());
    }

    @Test void globalMessagesOverrideEventMessagesAndCommandsExpandLiterally() {
        MutableClock clock = new MutableClock(NOW);
        var event = definition("xp", 1, 2, 1, 10);
        var config = new EventSettings(true, 300, 1, false, 1, true, List.of("Global {event_name}"), List.of("Global end"), List.of(event));
        var start = new EventEngine(config, clock, () -> 0).tick().getFirst();
        assertEquals(List.of("Global xp"), start.messages());
        assertEquals(List.of("say xp"), start.commands());
    }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
