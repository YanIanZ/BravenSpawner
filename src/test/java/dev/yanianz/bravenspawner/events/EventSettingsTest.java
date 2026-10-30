package dev.yanianz.bravenspawner.events;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EventSettingsTest {
    @Test void parsesBundledWindowsAndWinterSeason() throws Exception {
        try (var reader = new java.io.InputStreamReader(getClass().getResourceAsStream("/other/events.yml"))) {
            var yaml = new YamlConfiguration(); yaml.load(reader);
            var settings = EventSettings.parse(yaml, message -> fail(message));
            assertTrue(settings.enabled());
            assertEquals(3, settings.definitions().size());
            var winter = settings.definitions().get(2);
            assertTrue(winter.eligible(LocalDateTime.of(2026, 12, 5, 20, 0)));
            assertTrue(winter.eligible(LocalDateTime.of(2027, 1, 1, 23, 59)));
            assertFalse(winter.eligible(LocalDateTime.of(2027, 3, 5, 21, 0)));
            assertFalse(winter.eligible(LocalDateTime.of(2026, 12, 5, 19, 59)));
            assertFalse(winter.eligible(LocalDateTime.of(2026, 12, 6, 21, 0)));
        }
    }

    @Test void malformedIndividualEventIsSkippedWithoutDisablingValidEvents() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("spawner-events-enabled: true\nspawner-events:\n  broken:\n    xp-multiplier: .NaN\n    duration: 5\n  valid:\n    weight: 1\n    duration: 10\n");
        List<String> warnings = new ArrayList<>();
        var settings = EventSettings.parse(yaml, warnings::add);
        assertEquals(List.of("valid"), settings.definitions().stream().map(EventDefinition::id).toList());
        assertEquals(1, warnings.size());
    }
}
