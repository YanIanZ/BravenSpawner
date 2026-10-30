package dev.yanianz.bravenspawner.gui;

import dev.yanianz.bravenspawner.menu.MenuDefinition;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class BundledMenuCoverageTest {

    private static final List<String> MENUS = List.of("spawner", "storage", "buy_stack", "corruption", "log");
    private static final Pattern TOKEN = Pattern.compile("%([a-z_]+)%");

    private static YamlConfiguration load(String name) throws Exception {
        try (var stream = BundledMenuCoverageTest.class.getResourceAsStream("/gui/" + name + ".yml")) {
            assertNotNull(stream, name);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static MenuDefinition parse(String name, YamlConfiguration yaml) {
        return MenuDefinition.parse(name, yaml.getConfigurationSection("menu"), yaml.getConfigurationSection("items"));
    }

    @Test void everyPlaceholderInTheBundledMenusIsResolved() throws Exception {
        List<String> unknown = new ArrayList<>();
        for (String name : MENUS) {
            YamlConfiguration yaml = load(name);
            MenuDefinition definition = parse(name, yaml);
            List<String> texts = new ArrayList<>();
            texts.add(definition.title());
            texts.add(definition.option("special.booster_display", ""));
            texts.add(definition.option("selected_text", ""));
            texts.addAll(definition.lines("informative_lore"));
            for (MenuDefinition.ItemDefinition item : definition.itemsById().values()) {
                texts.add(item.name());
                texts.addAll(item.lore());
            }
            for (String text : texts) {
                Matcher matcher = TOKEN.matcher(text);
                while (matcher.find()) {
                    if (!MenuPlaceholders.KNOWN.contains(matcher.group(1))) unknown.add(name + ": %" + matcher.group(1) + "%");
                }
            }
        }
        assertEquals(List.of(), unknown);
    }

    @Test void theResolverFillsEveryKnownTokenOutsideTheBoosterLine() {
        Map<String, String> values = MenuPlaceholders.build(new MenuPlaceholders.Snapshot(
                "zombie", "Zombie", "&#ff6b6b", 0, 3, 100, 1234, 5000, 50, 0, "1.2K", "100", "6.4K",
                100, 300, 2, 6, 3, 9, 0, 1, "", ""));
        Set<String> expected = new HashSet<>(MenuPlaceholders.KNOWN);
        expected.removeAll(MenuPlaceholders.BOOSTER_LINE);
        assertTrue(values.keySet().containsAll(expected), () -> "missing " + expected.stream()
                .filter(key -> !values.containsKey(key)).toList());
        assertEquals("5-15", values.get("speed"));
        assertEquals("1,234/5,000", values.get("storage_value"));
        assertEquals("50", values.get("xp_value"), "unlimited XP shows the plain amount");
        assertEquals("2-6", values.get("drop_info"));
        assertEquals("Zombie", values.get("displayname"));
    }

    @Test void headTextureIsTheProductTexture() {
        String texture = "https://textures.minecraft.net/texture/b20657e24b56e1b2f8fc219da1de788c0c24f36388b1a409d0cd2d8dba44aa3b";
        Map<String, String> values = MenuPlaceholders.build(new MenuPlaceholders.Snapshot(
                "blaze", "Blaze", "&6", 0, 1, 100, 0, 0, 0, 0, "0", "0", "0",
                100, 100, 1, 2, 10, 10, 0, 1, texture, "PLAYER_HEAD"));
        assertEquals(texture, values.get("head_texture"));
        assertEquals("PLAYER_HEAD", values.get("head_material"));
    }

    @Test void everyBundledActionHasAHandler() throws Exception {
        List<String> unhandled = new ArrayList<>();
        for (String name : MENUS) {
            for (MenuDefinition.ItemDefinition item : parse(name, load(name)).itemsById().values()) {
                if (!item.action().isBlank() && GuiAction.parse(item.action()) == null) {
                    unhandled.add(name + "." + item.id() + " -> " + item.action());
                }
            }
        }
        assertEquals(List.of(), unhandled);
        assertEquals(GuiAction.BUY_STACK_MENU, GuiAction.parse("buystack"));
        assertEquals(GuiAction.SELL_ALL, GuiAction.parse("bedrock"));
    }

    @Test void pagedMenusHaveAContentAreaAboveTheControlRow() throws Exception {
        for (String name : List.of("storage", "log")) {
            MenuDefinition definition = parse(name, load(name));
            assertEquals(6, definition.rows(), name);
            for (String layout : definition.layouts().keySet()) {
                int cells = 0;
                for (int slot = 0; slot < 45; slot++) {
                    char symbol = definition.symbol(layout, slot);
                    if (symbol == 0 || symbol == definition.filler()) cells++;
                }
                assertEquals(45, cells, name + "/" + layout);
            }
        }
    }
}
