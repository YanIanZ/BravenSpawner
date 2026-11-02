package dev.yanianz.bravenspawner.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the shipped jar: runtime libraries are declared for the server's library
 * loader and none of them is bundled.
 *
 * <p>The jar used to shade and relocate these libraries (~20 MB). The library loader
 * downloads them instead, so a stray bundled copy would both bloat the jar and shadow
 * the downloaded version.
 */
class PluginJarTest {

    private static final List<String> LIBRARY_PACKAGES = List.of(
            "org/sqlite/", "com/mongodb/", "org/bson/", "com/zaxxer/", "com/mysql/", "com/google/protobuf/",
            "dev/yanianz/bravenspawner/libs/");

    private static Path pluginJar() {
        String path = System.getProperty("bravenspawner.pluginJar");
        Assumptions.assumeTrue(path != null && !path.isBlank(), "plugin jar path not provided");
        Path jar = Path.of(path);
        Assumptions.assumeTrue(Files.isRegularFile(jar), "plugin jar not built: " + jar);
        return jar;
    }

    @Test
    void declaresEveryRuntimeLibraryWithAnExactVersion() throws Exception {
        try (ZipFile zip = new ZipFile(pluginJar().toFile())) {
            ZipEntry entry = zip.getEntry("plugin.yml");
            assertNotNull(entry);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8));
            List<String> libraries = yaml.getStringList("libraries");
            assertEquals(List.of("org.mongodb:mongodb-driver-sync", "org.xerial:sqlite-jdbc",
                    "com.zaxxer:HikariCP", "com.mysql:mysql-connector-j"),
                    libraries.stream().map(c -> c.substring(0, c.lastIndexOf(':'))).toList());
            for (String coordinate : libraries) {
                assertTrue(coordinate.matches("[\\w.-]+:[\\w.-]+:\\d[\\w.-]*"), "exact version: " + coordinate);
            }
            assertFalse(yaml.getString("version", "").contains("$"), "version expanded");
        }
    }

    @Test
    void bundlesNoRuntimeLibrary() throws Exception {
        try (ZipFile zip = new ZipFile(pluginJar().toFile())) {
            List<String> bundled = zip.stream().map(ZipEntry::getName)
                    .filter(name -> LIBRARY_PACKAGES.stream().anyMatch(name::startsWith)).limit(5).toList();
            assertEquals(List.of(), bundled);
            assertNotNull(zip.getEntry("dev/yanianz/bravenspawner/BravenSpawner.class"));
        }
    }
}
