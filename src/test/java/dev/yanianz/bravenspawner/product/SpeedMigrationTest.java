package dev.yanianz.bravenspawner.product;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 1.5.0: unchanged bundled products get their own speed once; edited files are never touched. */
class SpeedMigrationTest {
    @TempDir Path folder;

    static final String OLD_ZOMBIE = "entity-type: ZOMBIE\n\n# Production period in ticks (20 ticks = 1 second); 1200 is once a minute.\ninterval-ticks: 1200\nxp: 5\n";
    static final String OLD_COW = "entity-type: COW\nspawn-delays:\n  min: 5\n  max: 15\nxp: 2\n";

    @Test void signaturesAndRewrite() {
        assertEquals("it:1200", SpeedMigration.signature(lines(OLD_ZOMBIE)));
        assertEquals("sd:5-15", SpeedMigration.signature(lines(OLD_COW)));
        assertNull(SpeedMigration.signature(lines("interval-ticks: 1\nspawn-delays:\n  min: 1\n  max: 2\n")), "ambiguous");
        List<String> block = List.of("# new", "spawn-delays:", "  min: 20", "  max: 35");
        List<String> out = SpeedMigration.rewrite(lines(OLD_ZOMBIE), block);
        assertEquals("sd:20-35", SpeedMigration.signature(out));
        assertFalse(String.join("\n", out).contains("Production period in ticks"), "the obsolete comment goes too");
        assertTrue(String.join("\n", out).contains("xp: 5"));
    }

    @Test void bundledBlockKeepsItsComments() {
        List<String> bundled = lines("lore: x\n\n# Production delay in seconds.\n# Tiers multiply it.\nspawn-delays:\n  min: 20\n  max: 35\nxp: 5\n");
        assertEquals(List.of("# Production delay in seconds.", "# Tiers multiply it.", "spawn-delays:", "  min: 20", "  max: 35"),
                SpeedMigration.bundledBlock(bundled));
    }

    @Test void onlyUntouchedFilesChangeAndOnlyOnce() throws Exception {
        File mobs = folder.resolve("mobs").toFile();
        assertTrue(mobs.mkdirs());
        Files.writeString(new File(mobs, "zombie.yml").toPath(), OLD_ZOMBIE);
        Files.writeString(new File(mobs, "cow.yml").toPath(), OLD_COW.replace("max: 15", "max: 12")); // edited by the admin
        Files.writeString(new File(mobs, "custom.yml").toPath(), "interval-ticks: 1200\n");                 // not bundled
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SpeedMigrationTest"));
        when(plugin.getResource(anyString())).thenAnswer(call -> switch ((String) call.getArgument(0)) {
            case SpeedMigration.PREVIOUS -> stream("mobs/zombie=it:1200\nmobs/cow=sd:5-15\n");
            case "mobs/zombie.yml" -> stream("entity-type: ZOMBIE\n\n# Production delay in seconds.\nspawn-delays:\n  min: 20\n  max: 35\nxp: 5\n");
            case "mobs/cow.yml" -> stream("entity-type: COW\n# Production delay in seconds.\nspawn-delays:\n  min: 15\n  max: 25\nxp: 2\n");
            default -> null;
        });

        assertEquals(1, SpeedMigration.run(plugin, mobs, "mobs"));
        assertEquals("sd:20-35", SpeedMigration.signature(Files.readAllLines(new File(mobs, "zombie.yml").toPath())));
        assertTrue(Files.readString(new File(mobs, "zombie.yml").toPath()).contains("entity-type: ZOMBIE"));
        assertEquals("sd:5-12", SpeedMigration.signature(Files.readAllLines(new File(mobs, "cow.yml").toPath())), "admin edit kept");
        assertEquals("it:1200", SpeedMigration.signature(Files.readAllLines(new File(mobs, "custom.yml").toPath())));
        assertTrue(new File(mobs, SpeedMigration.MARKER).isFile());

        Files.writeString(new File(mobs, "zombie.yml").toPath(), OLD_ZOMBIE);
        assertEquals(0, SpeedMigration.run(plugin, mobs, "mobs"), "runs once");
    }

    /** Every bundled product except the disabled bosses has its own speed range now. */
    @Test void bundledProductsHaveIndividualSpeeds() throws Exception {
        java.util.Set<String> ranges = new java.util.HashSet<>();
        for (File file : new File("src/main/resources/mobs").listFiles((d, n) -> n.endsWith(".yml"))) {
            String sig = SpeedMigration.signature(Files.readAllLines(file.toPath()));
            String id = file.getName().replace(".yml", "");
            if (List.of("ender_dragon", "wither", "giant", "illusioner").contains(id)) continue;
            assertNotNull(sig, id);
            assertTrue(sig.startsWith("sd:"), id + " uses spawn-delays");
            assertNotEquals("sd:60-60", sig, id);
            ranges.add(sig);
        }
        assertTrue(ranges.size() >= 25, "speeds vary across products: " + ranges.size());
    }

    private static List<String> lines(String text) {
        return List.of(text.split("\n", -1));
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
