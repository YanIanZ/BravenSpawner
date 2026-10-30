package dev.yanianz.bravenspawner.events;

import org.bukkit.configuration.ConfigurationSection;
import java.util.List;
import java.util.function.Consumer;

public record EventSettings(boolean enabled, long checkIntervalSeconds, double triggerChance,
                            boolean alwaysActive, int maxConcurrent, boolean globalMessages,
                            List<String> startMessages, List<String> endMessages,
                            List<EventDefinition> definitions) {
    public EventSettings {
        startMessages = List.copyOf(startMessages);
        endMessages = List.copyOf(endMessages);
        definitions = List.copyOf(definitions);
    }

    public static EventSettings parse(ConfigurationSection source, Consumer<String> warning) {
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(warning, "warning");
        long interval = source.getLong("check-interval", 300);
        if (interval < 1) { warning.accept("check-interval must be positive; using 1 second"); interval = 1; }
        double chance = source.getDouble("trigger-chance", .5);
        if (!Double.isFinite(chance) || chance < 0 || chance > 1) {
            warning.accept("trigger-chance must be inside 0..1; automatic chance disabled"); chance = 0;
        }
        int concurrency = source.getInt("max-concurrent-events", 1);
        if (concurrency < 1) { warning.accept("max-concurrent-events must be positive; using 1"); concurrency = 1; }
        java.util.ArrayList<EventDefinition> definitions = new java.util.ArrayList<>();
        ConfigurationSection events = source.getConfigurationSection("spawner-events");
        if (events != null) {
            for (String id : events.getKeys(false)) {
                try {
                    ConfigurationSection event = events.getConfigurationSection(id);
                    if (event == null) throw new IllegalArgumentException("expected a configuration section");
                    double xp = multiplier(event, "xp-multiplier");
                    double cash = multiplier(event, "cash-multiplier");
                    double weight = event.getDouble("weight", 0);
                    if (!Double.isFinite(weight)) throw new IllegalArgumentException("weight must be finite");
                    if (weight <= 0) continue;
                    long duration = event.getLong("duration");
                    if (duration <= 0) throw new IllegalArgumentException("duration must be positive");
                    java.util.Set<java.time.DayOfWeek> days = java.util.EnumSet.noneOf(java.time.DayOfWeek.class);
                    for (String day : event.getStringList("days")) {
                        days.add(java.time.DayOfWeek.valueOf(day.toUpperCase(java.util.Locale.ROOT)));
                    }
                    java.time.LocalTime start = null, end = null;
                    ConfigurationSection time = event.getConfigurationSection("time-range");
                    if (time != null) {
                        start = java.time.LocalTime.parse(java.util.Objects.requireNonNull(time.getString("start"), "time-range.start"));
                        end = java.time.LocalTime.parse(java.util.Objects.requireNonNull(time.getString("end"), "time-range.end"));
                    }
                    ConfigurationSection season = event.getConfigurationSection("season");
                    int startMonth = season == null ? 1 : season.getInt("start-month", 1);
                    int endMonth = season == null ? 12 : season.getInt("end-month", 12);
                    if (startMonth < 1 || startMonth > 12 || endMonth < 1 || endMonth > 12) {
                        throw new IllegalArgumentException("season months must be inside 1..12");
                    }
                    definitions.add(new EventDefinition(id, weight, xp, cash, duration, days,
                            start, end, startMonth, endMonth, event.getStringList("start-messages"),
                            event.getStringList("end-messages"), event.getStringList("start-commands"),
                            event.getStringList("end-commands")));
                } catch (RuntimeException invalid) {
                    warning.accept("Event '" + id + "' skipped: " + invalid.getMessage());
                }
            }
        }
        return new EventSettings(source.getBoolean("spawner-events-enabled", false), interval, chance,
                source.getBoolean("always-active", false), concurrency,
                source.getBoolean("global-messages.use-global-messages", false),
                source.getStringList("global-messages.event-start"), source.getStringList("global-messages.event-end"),
                definitions);
    }

    private static double multiplier(ConfigurationSection event, String key) {
        double value = event.getDouble(key, 1);
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException(key + " must be finite and nonnegative");
        return value;
    }
}
