package dev.yanianz.bravenspawner.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Every message key literal in the plugin source exists in the bundled messages.yml. */
class MessageKeysTest {
    private static final Pattern LITERAL = Pattern.compile("\"([a-z][a-z0-9-]*)\\.([a-z0-9][a-z0-9.-]*)\"");
    /** A literal directly passed to a config getter is a config path, not a message key. */
    private static final Pattern CONFIG_READ = Pattern.compile("\\.(get\\w*|is\\w*|contains|set)\\(\\s*$");

    @Test void everyReferencedKeyHasABundledDefault() throws IOException {
        YamlConfiguration bundled;
        try (var in = Messages.class.getResourceAsStream("/" + Messages.FILE_NAME)) {
            assertNotNull(in);
            bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Set<String> sections = bundled.getKeys(false);
        Set<String> missing = new TreeSet<>();
        int checked = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                // sounds.yml cue paths share section names with messages but live elsewhere.
                if (file.getFileName().toString().equals("SoundFeedback.java")) continue;
                String source = Files.readString(file);
                Matcher matcher = LITERAL.matcher(source);
                while (matcher.find()) {
                    if (matcher.group(2).endsWith("yml")) continue;
                    String before = source.substring(Math.max(0, matcher.start() - 80), matcher.start());
                    if (CONFIG_READ.matcher(before).find()) continue;
                    if (!sections.contains(matcher.group(1)) || !bundled.isConfigurationSection(matcher.group(1))) continue;
                    String key = matcher.group(1) + "." + matcher.group(2);
                    if (key.endsWith(".")) continue;
                    checked++;
                    if (!bundled.contains(key)) missing.add(key + " (" + file.getFileName() + ")");
                }
            }
        }
        assertTrue(checked > 100, "scanner found only " + checked + " keys");
        assertTrue(missing.isEmpty(), "missing message keys: " + missing);
    }
}
