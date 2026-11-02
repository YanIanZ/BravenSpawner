package dev.yanianz.bravenspawner.product;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-time update of product files to per-product speeds (1.5.0).
 *
 * <p>Until 1.4 most bundled products shared a 60 s (or 5–15 s) production delay.
 * 1.5.0 ships an individual delay range per product. A server's product files are
 * its own, so only files that still carry <em>exactly</em> the timing they were
 * shipped with (listed in {@code speeds-previous.properties}) are updated; anything
 * an administrator changed is left alone. Only the timing lines are replaced, so
 * every other edit and comment survives. A {@code .speeds-v2} marker makes this
 * run once per directory, and only catalogues initialized from the bundled
 * defaults are considered.
 */
final class SpeedMigration {

    static final String MARKER = ".speeds-v2";
    static final String PREVIOUS = "speeds-previous.properties";

    private static final Pattern INTERVAL = Pattern.compile("^interval-ticks:\\s*(\\d+)\\s*(#.*)?$");
    private static final Pattern DELAYS = Pattern.compile("^spawn-delays:\\s*(#.*)?$");
    private static final Pattern MIN = Pattern.compile("^\\s+min:\\s*(\\d+)\\s*(#.*)?$");
    private static final Pattern MAX = Pattern.compile("^\\s+max:\\s*(\\d+)\\s*(#.*)?$");
    private static final String OLD_COMMENT = "# Production period in ticks";

    private SpeedMigration() {}

    /** The timing a file declares: {@code it:<ticks>} or {@code sd:<min>-<max>}; null when ambiguous. */
    static String signature(List<String> lines) {
        String interval = null, delays = null;
        for (int i = 0; i < lines.size(); i++) {
            Matcher it = INTERVAL.matcher(lines.get(i));
            if (it.matches()) {
                if (interval != null) return null;
                interval = "it:" + it.group(1);
            } else if (DELAYS.matcher(lines.get(i)).matches()) {
                if (delays != null || i + 2 >= lines.size()) return null;
                Matcher min = MIN.matcher(lines.get(i + 1)), max = MAX.matcher(lines.get(i + 2));
                if (!min.matches() || !max.matches()) return null;
                delays = "sd:" + min.group(1) + "-" + max.group(1);
            }
        }
        if (interval != null && delays != null) return null;
        return interval != null ? interval : delays;
    }

    /** Replaces the timing lines with {@code block}; null when the layout is unexpected. */
    static List<String> rewrite(List<String> lines, List<String> block) {
        List<String> out = new ArrayList<>(lines);
        for (int i = 0; i < out.size(); i++) {
            if (INTERVAL.matcher(out.get(i)).matches()) {
                int start = i > 0 && out.get(i - 1).startsWith(OLD_COMMENT) ? i - 1 : i;
                out.subList(start, i + 1).clear();
                out.addAll(start, block);
                return out;
            }
            if (DELAYS.matcher(out.get(i)).matches()) {
                out.subList(i, i + 3).clear();
                out.addAll(i, block);
                return out;
            }
        }
        return null;
    }

    /** The new timing block of a bundled product: its comment lines and the spawn-delays section. */
    static List<String> bundledBlock(List<String> bundled) {
        for (int i = 0; i < bundled.size(); i++) {
            if (!DELAYS.matcher(bundled.get(i)).matches() || i + 2 >= bundled.size()) continue;
            int start = i;
            while (start > 0 && bundled.get(start - 1).startsWith("# ")
                    && !bundled.get(start - 1).startsWith("# chance")) start--;
            return List.copyOf(bundled.subList(start, i + 3));
        }
        return null;
    }

    /** Updates every untouched bundled product in {@code directory}; returns how many changed. */
    static int run(JavaPlugin plugin, File directory, String resourceDirectory) {
        File marker = new File(directory, MARKER);
        if (marker.exists()) return 0;
        Properties previous = new Properties();
        try (InputStream in = plugin.getResource(PREVIOUS)) {
            if (in == null) return 0;
            previous.load(in);
        } catch (IOException e) {
            return 0;
        }
        int updated = 0;
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String id = file.getName().substring(0, file.getName().length() - 4);
                String shipped = previous.getProperty(resourceDirectory + "/" + id);
                if (shipped == null) continue;
                try {
                    List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                    if (!shipped.equals(signature(lines))) continue;
                    List<String> block;
                    try (InputStream in = plugin.getResource(resourceDirectory + "/" + id + ".yml")) {
                        if (in == null) continue;
                        block = bundledBlock(List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)));
                    }
                    if (block == null) continue;
                    List<String> rewritten = rewrite(lines, block);
                    if (rewritten == null || shipped.equals(signature(rewritten))) continue;
                    File temp = new File(directory, file.getName() + ".tmp");
                    Files.write(temp.toPath(), rewritten, StandardCharsets.UTF_8);
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    updated++;
                } catch (IOException | RuntimeException e) {
                    plugin.getLogger().warning("could not update the speed of " + resourceDirectory + "/" + file.getName()
                            + ": " + e.getMessage());
                }
            }
        }
        try {
            Files.writeString(marker.toPath(), "per-product speeds applied to unchanged bundled files\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("could not write " + MARKER + ": " + e.getMessage());
        }
        if (updated > 0) {
            plugin.getLogger().info("Per-product speeds: updated " + updated + " unchanged bundled file(s) in "
                    + directory.getName() + "/ (files you edited were left as they are).");
        }
        return updated;
    }
}
