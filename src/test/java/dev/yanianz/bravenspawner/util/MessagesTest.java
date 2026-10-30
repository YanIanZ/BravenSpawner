package dev.yanianz.bravenspawner.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test void prefixRolesAndPlaceholdersResolve() {
        Messages messages = Messages.defaults();
        String text = plain(messages.component("modify.trusted", "player", "Steve"));
        assertTrue(text.startsWith("Braven » "), text);
        assertTrue(text.contains("Trusted Steve on this spawner."), text);
        assertFalse(text.contains("{"), text);
    }

    @Test void playerValuesCannotInjectFormattingOrPlaceholders() {
        Messages messages = Messages.defaults();
        String text = plain(messages.component("modify.trusted", "player", "<red>&4%player%"));
        assertTrue(text.contains("<red>&4%player%"), text);
    }

    @Test void rawValuesAreInsertedAsMarkup() {
        assertEquals("a <b>x</b>", Messages.fill("a %v%", "v", Messages.raw("<b>x</b>")));
    }

    @Test void missingKeysFallBackToTheKey() {
        assertEquals("no.such.key", Messages.defaults().format("no.such.key"));
    }
}
