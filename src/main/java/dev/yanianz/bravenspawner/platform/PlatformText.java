package dev.yanianz.bravenspawner.platform;

import dev.yanianz.bravenspawner.config.Settings;
import dev.yanianz.bravenspawner.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The single rendering entry point for text a player reads: menu titles, item
 * names, lore and hologram lines.
 *
 * <p>Colour is applied last, not first. PlaceholderAPI's contract is string in,
 * string out, and a placeholder such as {@code %vault_eco_balance_formatted%} may
 * return its own colour codes; translating ampersands before the parser runs
 * would push those codes through untranslated. Deserialising at the end with
 * {@link Text#of(String)} means every code in the final string — ours and the
 * expansion's — is honoured, and a placeholder can never emit a raw '&' into a
 * component.
 *
 * <p>PlaceholderAPI is only consulted when config.yml {@code placeholders-in-gui:}
 * is true: the switch exists to keep servers without the plugin, and servers that
 * want the cheaper render, off the parse path entirely.
 */
public final class PlatformText {

    private final Placeholders placeholders;
    private final boolean placeholdersInGui;

    public PlatformText(Settings settings, Placeholders placeholders) {
        this.placeholders = placeholders == null ? new Placeholders() : placeholders;
        this.placeholdersInGui = settings != null && settings.placeholdersInGui;
    }

    /** Whether this instance will actually call PlaceholderAPI for this render. */
    public boolean resolvingPlaceholders() {
        return placeholdersInGui && placeholders.available();
    }

    /** One line, colour resolved, ready for an inventory title or a hologram. */
    public Component line(Player viewer, String text) {
        return Text.of(legacy(viewer, text));
    }

    /** One line after placeholder resolution but before colouring; for text APIs. */
    public String legacy(Player viewer, String text) {
        if (text == null) return "";
        return placeholdersInGui ? placeholders.apply(viewer, text) : text;
    }

    public List<Component> lines(Player viewer, List<String> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<Component> out = new ArrayList<>(source.size());
        for (String line : source) out.add(line(viewer, line));
        return out;
    }

    public List<String> legacyLines(Player viewer, List<String> source) {
        if (source == null || source.isEmpty()) return List.of();
        if (!placeholdersInGui) return source;
        return placeholders.apply(viewer, source);
    }
}
