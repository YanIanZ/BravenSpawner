package dev.yanianz.bravenspawner.audit;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.util.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read side of the audit trail: turns {@code log.yml} and the daily files into the
 * strings a menu needs, without building an inventory.
 *
 * <p>Rendering belongs to the menu engine, so this class returns one prepared string
 * per event (the item name followed by its lore lines, newline separated). Keeping
 * the split here means the menu can swap to Books, chat or a web panel later without
 * a second parser for the audit file format.
 *
 * <p>Files are scanned newest-first and reading stops as soon as N matching events
 * are collected. A busy server can have months of files; loading them all to answer
 * one menu would put the whole trail on the heap for a page that shows 28 lines.
 */
public final class AuditMenu {

    /** Hard cap on rendered events regardless of what the YAML asks for. */
    public static final int MAX_RENDERED = 200;

    private static final String DEFAULT_TITLE = "&c> &8Audit Log &c<";
    private static final String DEFAULT_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final String DEFAULT_EVENT_NAME =
            "&e%time% &7| &f%actor% &7| &a%action%";
    private static final List<String> DEFAULT_EVENT_LORE = List.of(
            "&7Amount: &f%amount%",
            "&7Spawner: &f%spawner%",
            "&7Location: &f%world% %x%, %y%, %z%",
            "&7%detail%");

    /** One parsed event line; the menu engine consumes these values directly. */
    public record Template(String title, int rows, int limit, List<String> layout,
                           String timeFormat, String eventName, List<String> eventLore) {

        public Template {
            layout = List.copyOf(layout);
            eventLore = List.copyOf(eventLore);
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("%([a-z_]+)%");

    private final BravenSpawner plugin;
    private final File file;

    private volatile Template template;
    private volatile DateTimeFormatter timeFormatter;

    /**
     * Loads {@code log.yml}, shipping the packaged default on first use.
     *
     * <p>Construct on the enable thread: {@link #reload()} may write the missing
     * resource into the data folder, and a region thread must never wait on that
     * disk access.
     */
    public AuditMenu(BravenSpawner plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "log.yml");
        reload();
    }

    /**
     * Re-reads {@code log.yml}. Called after a config reload; a malformed file falls
     * back to the defaults rather than throwing into the reload path, because the
     * audit menu being stale is preferable to a reload that cannot complete.
     */
    public void reload() {
        Template parsed = load();
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(parsed.timeFormat(), Locale.ROOT);
        } catch (IllegalArgumentException e) {
            formatter = DateTimeFormatter.ofPattern(DEFAULT_TIME_FORMAT, Locale.ROOT);
        }
        this.template = parsed;
        this.timeFormatter = formatter;
    }

    /** Title, size, layout and per-event template from {@code log.yml}. */
    public Template template() {
        return template;
    }

    /**
     * The last {@code template.limit()} events for one spawner, newest first.
     *
     * @return menu-ready strings; empty when the spawner has no history or the log
     *         directory has not been created yet
     */
    public List<String> recent(UUID spawnerId) {
        return recent(spawnerId, template.limit());
    }

    /**
     * The last {@code limit} events for one spawner, newest first.
     *
     * <p>The in-memory history is consulted first: it holds the newest events even
     * while disk logging is off, and it lets the menu answer without touching disk
     * on a server whose audit files are cold. Older events come from the daily files,
     * with duplicates removed by identity so an event that was both recorded in memory
     * and flushed is rendered once.
     *
     * <p>The limit is clamped to {@value #MAX_RENDERED} so a config typo cannot make
     * one click scan and format an unbounded slice of the trail.
     */
    public List<String> recent(UUID spawnerId, int limit) {
        if (spawnerId == null) return List.of();
        int wanted = Math.max(1, Math.min(MAX_RENDERED, limit));
        Template current = template;

        List<AuditEvent> events = new ArrayList<>(wanted);
        Set<String> seen = new HashSet<>();
        AuditLog live = plugin.audit();
        if (live != null) {
            for (AuditEvent event : live.recent(spawnerId, wanted)) {
                events.add(event);
                seen.add(identity(event));
            }
        }
        if (events.size() < wanted) {
            // Ask for enough disk events to survive the overlap with memory history.
            int diskLimit = Math.min(MAX_RENDERED + 64, wanted + events.size());
            for (AuditEvent event : read(spawnerId, diskLimit)) {
                if (events.size() >= wanted) break;
                if (seen.add(identity(event))) events.add(event);
            }
        }

        List<String> rendered = new ArrayList<>(events.size());
        for (AuditEvent event : events) rendered.add(render(current, event));
        return rendered;
    }

    /** Identity used to drop a disk event that is already in the memory history. */
    private static String identity(AuditEvent event) {
        return event.timestamp() + "|" + event.action() + "|" + event.actor()
                + "|" + event.amount();
    }

    private Template load() {
        ensureFile();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection menu = yaml.getConfigurationSection("menu");

        String title = menu == null ? DEFAULT_TITLE : menu.getString("title", DEFAULT_TITLE);
        int rows = menu == null ? 6 : menu.getInt("rows", 6);
        rows = Math.max(1, Math.min(6, rows));

        int limit = menu == null ? rows * 7 : menu.getInt("limit", rows * 7);
        limit = Math.max(1, Math.min(MAX_RENDERED, limit));

        String timeFormat = menu == null ? DEFAULT_TIME_FORMAT
                : menu.getString("time-format", DEFAULT_TIME_FORMAT);
        List<String> layout = menu == null ? List.of() : readLayout(menu);

        ConfigurationSection event = yaml.getConfigurationSection("items.event");
        String eventName = event == null ? DEFAULT_EVENT_NAME
                : event.getString("name", DEFAULT_EVENT_NAME);
        List<String> eventLore = event == null
                ? DEFAULT_EVENT_LORE : event.getStringList("lore");
        if (eventLore.isEmpty()) eventLore = DEFAULT_EVENT_LORE;

        return new Template(title == null ? DEFAULT_TITLE : title, rows, limit, layout,
                timeFormat == null ? DEFAULT_TIME_FORMAT : timeFormat,
                eventName == null ? DEFAULT_EVENT_NAME : eventName, eventLore);
    }

    /**
     * Ships {@code log.yml} on first use. The bootstrap only saves config.yml, so a
     * server upgraded from an older build would otherwise render defaults forever
     * even though the packaged file exists.
     */
    private void ensureFile() {
        if (file.isFile()) return;
        try {
            plugin.saveResource("log.yml", false);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("could not write default log.yml: " + e.getMessage());
        }
    }

    /** Accepts the single-row string, a list of rows or a named layout section. */
    private static List<String> readLayout(ConfigurationSection menu) {
        Object raw = menu.get("layout");
        if (raw instanceof String row) return List.of(row);
        if (raw instanceof List<?> rows) {
            List<String> out = new ArrayList<>(rows.size());
            for (Object row : rows) if (row != null) out.add(String.valueOf(row));
            return out;
        }
        if (raw instanceof ConfigurationSection section) {
            List<String> preferred = section.getStringList("default");
            if (!preferred.isEmpty()) return preferred;
            for (String key : section.getKeys(false)) {
                List<String> rows = section.getStringList(key);
                if (!rows.isEmpty()) return rows;
            }
        }
        return List.of();
    }

    /**
     * Reads matching events from newest file to oldest, keeping only the newest
     * {@code limit} matches. Within one file the lines are chronological, so a
     * bounded deque keeps the tail without holding the whole file in memory.
     */
    private List<AuditEvent> read(UUID spawnerId, int limit) {
        File directory = AuditLog.logDirectory(plugin);
        File[] files = directory.listFiles((dir, name) ->
                name.startsWith("audit-") && name.endsWith(".log"));
        if (files == null || files.length == 0) return List.of();

        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        String wanted = spawnerId.toString();
        List<AuditEvent> collected = new ArrayList<>(limit);
        for (File candidate : files) {
            if (collected.size() >= limit) break;
            collected.addAll(lastMatches(candidate, wanted, limit - collected.size()));
        }
        return collected;
    }

    /** The newest {@code limit} matching events in one file, newest first. */
    private List<AuditEvent> lastMatches(File file, String spawnerId, int limit) {
        Deque<AuditEvent> tail = new ArrayDeque<>(limit);
        try (Stream<String> lines = java.nio.file.Files.lines(file.toPath(), StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                AuditEvent event = parse(line, spawnerId);
                if (event == null) return;
                if (tail.size() >= limit) tail.removeFirst();
                tail.addLast(event);
            });
        } catch (IOException | UncheckedIOException e) {
            plugin.getLogger().warning("audit menu could not read " + file.getName()
                    + ": " + e.getMessage());
        }
        List<AuditEvent> out = new ArrayList<>(tail.size());
        for (var iterator = tail.descendingIterator(); iterator.hasNext(); ) {
            out.add(iterator.next());
        }
        return out;
    }

    /**
     * Parses one line, rejecting anything that is not a complete audit record.
     *
     * <p>The spawner id is checked before the numeric columns so the common case
     * (a file full of other spawners) costs one string comparison, not seven
     * {@code parseInt} failures.
     */
    private static AuditEvent parse(String line, String spawnerId) {
        String[] parts = line.split("\\|", -1);
        if (parts.length < 11 || !parts[4].equalsIgnoreCase(spawnerId)) return null;
        try {
            long timestamp = Long.parseLong(parts[0]);
            AuditEvent.Action action = AuditEvent.Action.valueOf(parts[1]);
            UUID actor = UUID.fromString(parts[2]);
            UUID spawner = UUID.fromString(parts[4]);
            int x = Integer.parseInt(parts[6]);
            int y = Integer.parseInt(parts[7]);
            int z = Integer.parseInt(parts[8]);
            long amount = Long.parseLong(parts[9]);
            return new AuditEvent(timestamp, actor, parts[3], action, spawner,
                    parts[5], x, y, z, amount, parts[10]);
        } catch (IllegalArgumentException e) {
            // Truncated or hand-edited line: skip it instead of failing the page.
            return null;
        }
    }

    private String render(Template template, AuditEvent event) {
        Map<String, String> values = new HashMap<>(12);
        values.put("time", timeFormatter.format(
                Instant.ofEpochMilli(event.timestamp()).atZone(ZoneId.systemDefault())));
        values.put("actor", event.actorName().isEmpty() ? event.actor().toString() : Text.escape(event.actorName()));
        values.put("action", event.action().name());
        values.put("amount", Long.toString(event.amount()));
        values.put("spawner", event.spawnerId().toString());
        values.put("world", event.world().isEmpty() ? "-" : Text.escape(event.world()));
        values.put("x", Integer.toString(event.x()));
        values.put("y", Integer.toString(event.y()));
        values.put("z", Integer.toString(event.z()));
        values.put("detail", Text.escape(event.detail()));

        StringBuilder out = new StringBuilder(fill(template.eventName(), values));
        for (String line : template.eventLore()) {
            String rendered = fill(line, values);
            // A lore line whose only content was a placeholder that resolved to an
            // empty detail would render as an empty hover row; drop those.
            if (Text.strip(rendered).isBlank()) continue;
            out.append('\n').append(rendered);
        }
        return out.toString();
    }

    /**
     * One-pass placeholder replacement.
     *
     * <p>Replacing key by key would let a player name containing {@code %amount%}
     * substitute the following key into its own value; matching the template once
     * means replacement text is never re-scanned.
     */
    private static String fill(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
