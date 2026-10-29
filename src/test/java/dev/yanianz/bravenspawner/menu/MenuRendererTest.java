package dev.yanianz.bravenspawner.menu;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuRendererTest {
    @Test void placeholderValuesAreLiteralAndDoNotExpandOtherTokens() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("entity", "%price%");
        values.put("price", "100");
        Method substitute = MenuRenderer.class.getDeclaredMethod("substitute", String.class, Map.class);
        substitute.setAccessible(true);
        assertEquals("%price% costs 100", substitute.invoke(null, "%entity% costs %price%", values));
    }
}
