package dev.yanianz.bravenspawner.wand;

import dev.yanianz.bravenspawner.BravenSpawner;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The three sell-wand cues, read from {@code sounds.yml}.
 *
 * <p>Sounds are resolved through the Bukkit enum at load time, not by sending a
 * raw string to the client. A typo therefore falls back to a known cue and is
 * logged once, instead of producing a silent interaction the admin cannot
 * diagnose. The fallback also covers a server version that removed a sound name:
 * {@link Sound#valueOf} throws, and the cue still plays.
 *
 * <p>Playback is direct because every caller is already on the player's region
 * thread (an interact event or a command). Scheduling again would only add a tick
 * of latency between the click and the feedback.
 */
public final class SoundFeedback {

    /** The three moments the wand reports. */
    public enum Cue { SUCCESS, FAILURE, COOLDOWN }

    public static final String FILE_NAME = "sounds.yml";

    private static final Sound FALLBACK_SUCCESS = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
    private static final Sound FALLBACK_FAILURE = Sound.ENTITY_VILLAGER_NO;
    private static final Sound FALLBACK_COOLDOWN = Sound.BLOCK_NOTE_BLOCK_BASS;

    private record Profile(Sound sound, float volume, float pitch) {}

    private final BravenSpawner plugin;
    private final Map<Cue, Profile> profiles = new EnumMap<>(Cue.class);

    public SoundFeedback(BravenSpawner plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Re-reads the file; safe to call from the install/reload path only. */
    public void reload() {
        YamlConfiguration root = WandFiles.load(plugin, FILE_NAME);
        Set<String> warned = new HashSet<>();
        profiles.clear();
        profiles.put(Cue.SUCCESS, read(root, "sell.wand.success", "sell.xp", FALLBACK_SUCCESS, warned));
        profiles.put(Cue.FAILURE, read(root, "sell.wand.failure", "error", FALLBACK_FAILURE, warned));
        profiles.put(Cue.COOLDOWN, read(root, "sell.wand.cooldown", null, FALLBACK_COOLDOWN, warned));
    }

    private Profile read(YamlConfiguration root, String path, String legacyPath,
                         Sound fallback, Set<String> warned) {
        ConfigurationSection section = root.getConfigurationSection(path);
        if (section == null && legacyPath != null) {
            // The reference file predates the sell-wand section; honour its keys so
            // an existing sounds.yml keeps working after the upgrade.
            section = root.getConfigurationSection(legacyPath);
        }
        if (section == null) {
            WandFiles.warnOnce(plugin, warned, path,
                    FILE_NAME + " has no " + path + "; using " + fallback.name() + ".");
            return new Profile(fallback, 1.0f, 1.0f);
        }

        String rawSound = section.getString("sound");
        Sound sound = resolve(rawSound, fallback, path, warned);

        double volume = section.getDouble("volume", 1.0);
        if (!Double.isFinite(volume) || volume <= 0.0) volume = 1.0;
        double pitch = section.getDouble("pitch", 1.0);
        if (!Double.isFinite(pitch) || pitch <= 0.0) pitch = 1.0;

        // Minecraft accepts volume above 1 (it widens the audible radius) and pitch
        // only inside 0.5..2.0; clamping here keeps a bad config from throwing.
        return new Profile(sound,
                (float) Math.min(4.0, volume),
                (float) Math.min(2.0, Math.max(0.5, pitch)));
    }

    /** Resolves a configured name, falling back when it is absent or unknown. */
    private Sound resolve(String raw, Sound fallback, String path, Set<String> warned) {
        if (raw == null || raw.isBlank()) {
            WandFiles.warnOnce(plugin, warned, path + ".sound",
                    FILE_NAME + " " + path + ".sound is empty; using " + fallback.name() + ".");
            return fallback;
        }
        try {
            return Sound.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            WandFiles.warnOnce(plugin, warned, path + ".sound",
                    FILE_NAME + " " + path + ".sound '" + raw + "' is not a Bukkit Sound; using "
                            + fallback.name() + ".");
            return fallback;
        }
    }

    /** Plays one cue; a null player or cue is a no-op so callers need no guard. */
    public void play(Player player, Cue cue) {
        if (player == null || cue == null) return;
        Profile profile = profiles.get(cue);
        if (profile == null) return;
        player.playSound(player.getLocation(), profile.sound(), profile.volume(), profile.pitch());
    }
}
