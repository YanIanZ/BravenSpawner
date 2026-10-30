package dev.yanianz.bravenspawner.events;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

public record EventDefinition(String id, double weight, double xpMultiplier, double cashMultiplier,
                              long durationSeconds, Set<DayOfWeek> days,
                              LocalTime startTime, LocalTime endTime, int startMonth, int endMonth,
                              List<String> startMessages, List<String> endMessages,
                              List<String> startCommands, List<String> endCommands) {
    public EventDefinition {
        days = Set.copyOf(days);
        startMessages = List.copyOf(startMessages);
        endMessages = List.copyOf(endMessages);
        startCommands = List.copyOf(startCommands);
        endCommands = List.copyOf(endCommands);
    }

    public boolean eligible(LocalDateTime time) {
        if (!days.isEmpty() && !days.contains(time.getDayOfWeek())) return false;
        int month = time.getMonthValue();
        boolean inSeason = startMonth <= endMonth
                ? month >= startMonth && month <= endMonth : month >= startMonth || month <= endMonth;
        if (!inSeason) return false;
        // The reference uses same-day AND bounds, including exact endpoints.
        return startTime == null || !time.toLocalTime().isBefore(startTime)
                && !time.toLocalTime().isAfter(endTime);
    }
}
