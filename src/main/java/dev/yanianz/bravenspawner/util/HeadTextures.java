package dev.yanianz.bravenspawner.util;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom player-head textures without a Mojang lookup.
 *
 * <p>A configured texture may be a {@code textures.minecraft.net} URL, the bare
 * texture hash, a base64 {@code textures} payload or the raw JSON. All of them are
 * normalised to one base64 payload, which is the only thing a skull needs: the
 * profile gets a stable id and name derived from that payload plus the
 * {@code textures} property, so the server never has to complete it.
 *
 * <p>Built profiles are cached per payload; the cache is bounded and safe to use
 * from any region thread.
 */
public final class HeadTextures {

    /** Menu token that stands for "the head of this spawner's product". */
    public static final String SPAWNER_HEAD_TOKEN = "spawnerhead";

    private static final String TEXTURE_BASE = "http://textures.minecraft.net/texture/";
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{32,128}");
    private static final Pattern TEXTURE_URL = Pattern.compile(
            "(?i)^https?://textures\\.minecraft\\.net/texture/([0-9a-f]{32,128})/?$");
    private static final Pattern JSON_URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final int CACHE_LIMIT = 512;
    private static final Map<String, PlayerProfile> PROFILES = new ConcurrentHashMap<>();

    private HeadTextures() {}

    /** True for the {@code spawnerhead} token, case-insensitive. */
    public static boolean isSpawnerToken(String raw) {
        return raw != null && raw.trim().equalsIgnoreCase(SPAWNER_HEAD_TOKEN);
    }

    /**
     * Normalises a configured texture to its base64 {@code textures} payload.
     *
     * @return the payload, or null for an empty, unresolved ({@code %...%}) or
     *         unrecognised value
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty() || value.indexOf('%') >= 0 || isSpawnerToken(value)) return null;

        if (value.startsWith("{")) {
            return value.contains("\"textures\"") ? encode(value) : null;
        }
        if (value.regionMatches(true, 0, "http://", 0, 7) || value.regionMatches(true, 0, "https://", 0, 8)) {
            Matcher url = TEXTURE_URL.matcher(value);
            return url.matches() ? encodeUrl(url.group(1)) : null;
        }
        if (HASH.matcher(value).matches()) return encodeUrl(value);

        String compact = value.replaceAll("\\s+", "");
        String json = decode(compact);
        if (json == null || !json.contains("\"textures\"")) return null;
        return compact;
    }

    /** The skin URL inside a payload, or null; used by tests and diagnostics. */
    public static String skinUrl(String payload) {
        String json = decode(payload);
        if (json == null) return null;
        Matcher matcher = JSON_URL.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Stable profile id: the same texture always maps to the same identity. */
    public static UUID profileId(String payload) {
        return UUID.nameUUIDFromBytes(("BravenSpawner:" + payload).getBytes(StandardCharsets.UTF_8));
    }

    /** A valid player name (16 characters, {@code [a-z0-9_]}) derived from the payload. */
    public static String profileName(String payload) {
        String hex = profileId(payload).toString().replace("-", "");
        return "bs_" + hex.substring(0, 13);
    }

    /**
     * The material a menu should use for the spawner's head icon.
     *
     * <p>A {@code PLAYER_HEAD} without a usable texture would render as the default
     * skin, which is exactly the "head does not change" symptom; such products fall
     * back to a spawner block instead.
     */
    public static Material iconMaterial(Material headMaterial, String texture) {
        if (headMaterial == null || headMaterial == Material.AIR) {
            return normalize(texture) != null ? Material.PLAYER_HEAD : Material.SPAWNER;
        }
        if (headMaterial == Material.PLAYER_HEAD && normalize(texture) == null) return Material.SPAWNER;
        return headMaterial;
    }

    /**
     * Applies a texture to a skull.
     *
     * <p>Only the profile property is written; no lookup or completion is started,
     * so this is safe on any thread that may touch the item.
     *
     * @return false when the value is not a usable texture (the skull is unchanged)
     */
    public static boolean apply(SkullMeta meta, String raw) {
        if (meta == null) return false;
        String payload = normalize(raw);
        if (payload == null) return false;
        PlayerProfile profile = profile(payload);
        if (profile == null) return false;
        meta.setPlayerProfile(profile);
        return true;
    }

    /** Drops cached profiles; called on reload. */
    public static void clearCache() {
        PROFILES.clear();
    }

    private static PlayerProfile profile(String payload) {
        PlayerProfile cached = PROFILES.get(payload);
        if (cached != null) return cached;
        PlayerProfile created;
        try {
            created = Bukkit.createProfile(profileId(payload), profileName(payload));
            created.setProperty(new ProfileProperty("textures", payload));
        } catch (RuntimeException e) {
            return null;
        }
        if (PROFILES.size() >= CACHE_LIMIT) PROFILES.clear();
        PROFILES.putIfAbsent(payload, created);
        return created;
    }

    private static String encodeUrl(String hash) {
        return encode("{\"textures\":{\"SKIN\":{\"url\":\"" + TEXTURE_BASE + hash.toLowerCase(Locale.ROOT) + "\"}}}");
    }

    private static String encode(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException standard) {
            try {
                return new String(Base64.getUrlDecoder().decode(base64), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException urlSafe) {
                return null;
            }
        }
    }
}
