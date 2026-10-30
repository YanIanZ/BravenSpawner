package dev.yanianz.bravenspawner.events;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic event state; contains no server commands, scheduling, or player access. */
public final class EventEngine {
    public enum Phase { START, END }
    public record ActiveEvent(EventDefinition definition, Instant endsAt) { }
    public record Transition(Phase phase, EventDefinition definition, List<String> messages, List<String> commands) {
        public Transition {
            messages = List.copyOf(messages);
            commands = List.copyOf(commands);
        }
    }

    private static final Pattern TOKENS = Pattern.compile("\\{(event_name|duration|xp_multiplier|cash_multiplier)\\}");
    private final Clock clock;
    private final DoubleSupplier random;
    private final Map<String, ActiveEvent> active = new LinkedHashMap<>();
    private EventSettings settings;
    private Instant nextCheck;

    public EventEngine(EventSettings settings, Clock clock, DoubleSupplier random) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        nextCheck = clock.instant();
    }

    public synchronized List<Transition> tick() {
        if (!settings.enabled()) return List.of();
        Instant now = clock.instant();
        List<Transition> changes = new ArrayList<>();
        var iterator = active.values().iterator();
        while (iterator.hasNext()) {
            ActiveEvent event = iterator.next();
            if (!event.endsAt().isAfter(now)) {
                iterator.remove();
                changes.add(transition(Phase.END, event.definition()));
            }
        }
        boolean due = !now.isBefore(nextCheck);
        boolean replace = settings.alwaysActive() && !changes.isEmpty();
        if (due) nextCheck = deadline(now, settings.checkIntervalSeconds());
        if ((due || replace) && active.size() < settings.maxConcurrent()
                && !settings.definitions().isEmpty()
                && (settings.alwaysActive() || roll() < settings.triggerChance())) {
            EventDefinition selected = choose();
            if (selected != null && !active.containsKey(selected.id())
                    && selected.eligible(LocalDateTime.ofInstant(now, clock.getZone()))) {
                active.put(selected.id(), new ActiveEvent(selected, deadline(now, selected.durationSeconds())));
                changes.add(transition(Phase.START, selected));
            }
        }
        return List.copyOf(changes);
    }

    /** Reload/disable clears silently; normal expiry alone emits end effects. */
    public synchronized List<Transition> configure(EventSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        clear();
        return List.of();
    }

    public synchronized void clear() {
        active.clear();
        nextCheck = clock.instant();
    }

    public synchronized List<ActiveEvent> activeEvents() {
        if (!settings.enabled()) return List.of();
        Instant now = clock.instant();
        return active.values().stream().filter(event -> event.endsAt().isAfter(now)).toList();
    }

    public synchronized double xpMultiplier() { return multiplier(true); }
    public synchronized double cashMultiplier() { return multiplier(false); }

    private double multiplier(boolean xp) {
        double result = 1;
        for (ActiveEvent event : activeEvents()) {
            double value = xp ? event.definition().xpMultiplier() : event.definition().cashMultiplier();
            result *= value;
            if (!Double.isFinite(result)) result = Double.MAX_VALUE;
        }
        return result;
    }

    private EventDefinition choose() {
        // Scaling avoids overflow when administrators configure very large finite weights.
        double scale = settings.definitions().stream().mapToDouble(EventDefinition::weight).max().orElse(0);
        if (scale <= 0 || !Double.isFinite(scale)) return null;
        double total = settings.definitions().stream().mapToDouble(event -> event.weight() / scale).sum();
        double target = roll() * total;
        double cumulative = 0;
        for (EventDefinition event : settings.definitions()) {
            cumulative += event.weight() / scale;
            if (target < cumulative) return event;
        }
        return null;
    }

    private double roll() {
        double value = random.getAsDouble();
        if (!Double.isFinite(value) || value < 0 || value >= 1) {
            throw new IllegalArgumentException("random supplier must return inside [0, 1)");
        }
        return value;
    }

    private Transition transition(Phase phase, EventDefinition event) {
        boolean start = phase == Phase.START;
        List<String> messages = settings.globalMessages()
                ? start ? settings.startMessages() : settings.endMessages()
                : start ? event.startMessages() : event.endMessages();
        List<String> commands = start ? event.startCommands() : event.endCommands();
        Transition context = new Transition(phase, event, List.of(), List.of());
        return new Transition(phase, event, messages.stream().map(line -> expand(line, context)).toList(),
                commands.stream().map(line -> expand(line, context)).toList());
    }

    public static String expand(String text, Transition transition) {
        if (text == null) return "";
        EventDefinition event = transition.definition();
        boolean start = transition.phase() == Phase.START;
        Matcher matcher = TOKENS.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = switch (matcher.group(1)) {
                case "event_name" -> event.id();
                case "duration" -> String.valueOf(start ? event.durationSeconds() : 0);
                case "xp_multiplier" -> String.valueOf(start ? event.xpMultiplier() : 1.0);
                case "cash_multiplier" -> String.valueOf(start ? event.cashMultiplier() : 1.0);
                default -> matcher.group();
            };
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Instant deadline(Instant now, long seconds) {
        try { return now.plusSeconds(seconds); }
        catch (java.time.DateTimeException | ArithmeticException overflow) { return Instant.MAX; }
    }
}
