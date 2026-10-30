package dev.yanianz.bravenspawner.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Opens SQLite through the shipped (shaded) jar in an isolated class loader.
 *
 * <p>Relocating {@code org.sqlite} renames the JNI classes but not the symbols inside
 * the bundled native library, which fails at the first connection with
 * {@code UnsatisfiedLinkError: NativeDB._open_utf8}. The unshaded test classpath cannot
 * see that, so this test runs against the real artifact.
 */
class ShadedSqliteTest {

    private static Path shadedJar() {
        String path = System.getProperty("bravenspawner.shadedJar");
        Assumptions.assumeTrue(path != null && !path.isBlank(), "shaded jar path not provided");
        Path jar = Path.of(path);
        Assumptions.assumeTrue(Files.isRegularFile(jar), "shaded jar not built: " + jar);
        return jar;
    }

    @Test
    void sqliteIsNotRelocated() throws Exception {
        try (ZipFile zip = new ZipFile(shadedJar().toFile())) {
            assertNull(zip.getEntry("dev/yanianz/bravenspawner/libs/sqlite/JDBC.class"),
                    "org.sqlite must not be relocated: its native library binds the original JNI names");
            assertNotNull(zip.getEntry("org/sqlite/JDBC.class"));
            assertNotNull(zip.getEntry("org/sqlite/native/Linux/x86_64/libsqlitejdbc.so"));
        }
    }

    @Test
    void shadedDriverOpensADatabase() throws Exception {
        URL url = shadedJar().toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{url}, ClassLoader.getPlatformClassLoader())) {
            Driver driver = (Driver) Class.forName("org.sqlite.JDBC", true, loader)
                    .getDeclaredConstructor().newInstance();
            try (Connection c = driver.connect("jdbc:sqlite::memory:", new Properties());
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }
}
