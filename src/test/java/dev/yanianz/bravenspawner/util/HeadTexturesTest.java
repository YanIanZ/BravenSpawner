package dev.yanianz.bravenspawner.util;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadTexturesTest {

    private static final String HASH = "b20657e24b56e1b2f8fc219da1de788c0c24f36388b1a409d0cd2d8dba44aa3b";
    private static final String CANONICAL = "http://textures.minecraft.net/texture/" + HASH;

    @Test void urlHashAndBase64AllNormaliseToTheSamePayload() {
        String fromHttps = HeadTextures.normalize("https://textures.minecraft.net/texture/" + HASH);
        String fromHttp = HeadTextures.normalize(CANONICAL);
        String fromHash = HeadTextures.normalize(HASH);
        String fromUpper = HeadTextures.normalize(HASH.toUpperCase());
        assertEquals(fromHash, fromHttps);
        assertEquals(fromHash, fromHttp);
        assertEquals(fromHash, fromUpper);
        assertEquals(fromHash, HeadTextures.normalize(fromHash), "a payload is kept as is");
        assertEquals(CANONICAL, HeadTextures.skinUrl(fromHash));
        String json = new String(Base64.getDecoder().decode(fromHash), StandardCharsets.UTF_8);
        assertEquals("{\"textures\":{\"SKIN\":{\"url\":\"" + CANONICAL + "\"}}}", json);
    }

    @Test void rawJsonAndWrappedBase64AreAccepted() {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + CANONICAL + "\"}}}";
        String payload = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(payload, HeadTextures.normalize(json));
        String wrapped = payload.substring(0, 20) + "\n  " + payload.substring(20);
        assertEquals(payload, HeadTextures.normalize(wrapped));
    }

    @Test void unusableValuesAreRejected() {
        assertNull(HeadTextures.normalize(null));
        assertNull(HeadTextures.normalize("  "));
        assertNull(HeadTextures.normalize("%head_texture%"));
        assertNull(HeadTextures.normalize("spawnerhead"));
        assertNull(HeadTextures.normalize("https://example.com/texture/" + HASH));
        assertNull(HeadTextures.normalize("not a texture"));
        assertNull(HeadTextures.normalize(Base64.getEncoder().encodeToString("{\"a\":1}".getBytes(StandardCharsets.UTF_8))));
    }

    @Test void profileIdentityIsStableAndAValidName() {
        String payload = HeadTextures.normalize(HASH);
        assertEquals(HeadTextures.profileId(payload), HeadTextures.profileId(HeadTextures.normalize(CANONICAL)));
        assertNotEquals(HeadTextures.profileId(payload), HeadTextures.profileId(HeadTextures.normalize("ab" + HASH.substring(2))));
        String name = HeadTextures.profileName(payload);
        assertTrue(name.length() <= 16 && name.matches("[a-z0-9_]+"), name);
    }

    @Test void iconFallsBackToASpawnerWhenAPlayerHeadHasNoSkin() {
        assertEquals(Material.SPAWNER, HeadTextures.iconMaterial(Material.PLAYER_HEAD, null));
        assertEquals(Material.SPAWNER, HeadTextures.iconMaterial(null, ""));
        assertEquals(Material.PLAYER_HEAD, HeadTextures.iconMaterial(Material.PLAYER_HEAD, HASH));
        assertEquals(Material.PLAYER_HEAD, HeadTextures.iconMaterial(null, HASH));
        assertEquals(Material.COBBLESTONE, HeadTextures.iconMaterial(Material.COBBLESTONE, null));
    }
}
