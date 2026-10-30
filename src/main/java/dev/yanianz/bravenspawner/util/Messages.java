package dev.yanianz.bravenspawner.util;

import dev.yanianz.bravenspawner.BravenSpawner;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Player-facing text from {@code messages.yml}.
 *
 * <p>Templates name colour roles ({@code {success}}, {@code {error}}, …) instead of
 * raw codes, so an operator restyles every message from the {@code roles:} block.
 * Placeholders are {@code %key%} and are filled in one pass; values are escaped
 * unless wrapped in {@link #raw(String)}, so a player name can never inject a
 * colour, a tag or another placeholder. A key missing from the server's copy
 * falls back to the bundled file, then to the key itself.
 */
public final class Messages {

    /** A value that is already trusted markup (a money string, a formatted reason). */
    public record Raw(String value) { }

    public static final String FILE_NAME = "messages.yml";

    private static final Pattern PLACEHOLDER = Pattern.compile("%([A-Za-z0-9_]+)%");
    private static final Pattern ROLE = Pattern.compile("\\{([a-z_]+)}");
    private static final Map<Object, WeakReference<Messages>> SHARED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final BravenSpawner plugin;
    private final YamlConfiguration defaults;
    private volatile ConfigurationSection active;

    private Messages(BravenSpawner plugin) {
        this.plugin = plugin;
        this.defaults = bundled();
        reload();
    }

    /** Shared instance for this plugin; created and loaded on first use. */
    public static Messages of(BravenSpawner plugin) {
        Object key = plugin == null ? Messages.class : plugin;
        synchronized (SHARED) {
            WeakReference<Messages> reference = SHARED.get(key);
            Messages existing = reference == null ? null : reference.get();
            if (existing != null) return existing;
            Messages created = new Messages(plugin);
            SHARED.put(key, new WeakReference<>(created));
            return created;
        }
    }

    /** Bundled defaults only; used by tests and before the data folder exists. */
    public static Messages defaults() {
        return of(null);
    }

    public static Raw raw(String value) {
        return new Raw(value == null ? "" : value);
    }

    /** Re-reads the server copy, writing the bundled file on first run. */
    public void reload() {
        ConfigurationSection loaded = defaults;
        try {
            File folder = plugin == null ? null : plugin.getDataFolder();
            if (folder != null) {
                File file = new File(folder, FILE_NAME);
                if (!file.isFile()) plugin.saveResource(FILE_NAME, false);
                if (file.isFile()) {
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                    yaml.setDefaults(defaults);
                    loaded = yaml;
                }
            }
        } catch (RuntimeException unreadable) {
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().warning(FILE_NAME + " could not be read (" + unreadable.getMessage()
                        + "); bundled messages are in effect.");
            }
        }
        active = loaded;
    }

    private static YamlConfiguration bundled() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream stream = Messages.class.getResourceAsStream("/" + FILE_NAME)) {
            if (stream != null) yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception broken) {
            // A jar without the resource still renders every key literally.
        }
        return yaml;
    }

    /** True when the key exists in the server copy or the bundled defaults. */
    public boolean has(String key) {
        return active.getString(key) != null || defaults.getString(key) != null;
    }

    /** Raw template for a key (roles and placeholders unresolved). */
    public String template(String key) {
        String value = active.getString(key);
        if (value == null) value = defaults.getString(key);
        return value == null ? key : value;
    }

    /** Message text with roles and placeholders resolved, without the prefix. */
    public String format(String key, Object... values) {
        return fill(roles(template(key)), values);
    }

    /** Every line of a list key, resolved; a scalar key yields one line. */
    public List<String> lines(String key, Object... values) {
        List<String> raw = active.isList(key) ? active.getStringList(key) : defaults.getStringList(key);
        if (raw.isEmpty()) return List.of(format(key, values));
        List<String> out = new ArrayList<>(raw.size());
        for (String line : raw) out.add(fill(roles(line), values));
        return out;
    }

    public String prefix() {
        return roles(template("prefix"));
    }

    /** Prefixed chat component. */
    public Component component(String key, Object... values) {
        return Text.of(prefix() + format(key, values));
    }

    public void send(CommandSender target, String key, Object... values) {
        if (target != null) target.sendMessage(component(key, values));
    }

    /** Sends without the prefix, for continuation lines such as list entries. */
    public void sendPlain(CommandSender target, String key, Object... values) {
        if (target != null) target.sendMessage(Text.of(format(key, values)));
    }

    /** Sends every line of a list key; the first line carries the prefix. */
    public void sendLines(CommandSender target, String key, Object... values) {
        if (target == null) return;
        boolean first = true;
        for (String line : lines(key, values)) {
            target.sendMessage(Text.of(first ? prefix() + line : line));
            first = false;
        }
    }

    private String roles(String template) {
        if (template.indexOf('{') < 0) return template;
        Matcher matcher = ROLE.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 16);
        while (matcher.find()) {
            String path = "roles." + matcher.group(1);
            String role = active.getString(path);
            if (role == null) role = defaults.getString(path);
            matcher.appendReplacement(out, Matcher.quoteReplacement(role == null ? matcher.group() : role));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** One-pass fill; replacement text is never re-scanned for placeholders. */
    static String fill(String template, Object... values) {
        if (values == null || values.length == 0 || template.indexOf('%') < 0) return template;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 16);
        while (matcher.find()) {
            Object value = lookup(matcher.group(1), values);
            String replacement;
            if (value == NOT_FOUND) replacement = matcher.group();
            else if (value instanceof Raw raw) replacement = raw.value();
            else replacement = Text.escape(String.valueOf(value));
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static final Object NOT_FOUND = new Object();

    private static Object lookup(String key, Object... values) {
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (key.equals(String.valueOf(values[i]))) return values[i + 1];
        }
        return NOT_FOUND;
    }
}
