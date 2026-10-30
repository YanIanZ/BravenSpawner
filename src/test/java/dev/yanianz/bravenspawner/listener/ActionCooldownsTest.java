package dev.yanianz.bravenspawner.listener;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class ActionCooldownsTest {
    @Test void secondActionInsideTheWindowIsRefused() {
        ActionCooldowns gate = new ActionCooldowns();
        UUID player = UUID.randomUUID();
        assertEquals(0, gate.acquire(player, 300, 1_000));
        assertEquals(200, gate.acquire(player, 300, 1_100));
        assertEquals(0, gate.acquire(player, 300, 1_300));
        assertEquals(0, gate.acquire(UUID.randomUUID(), 300, 1_301), "per player");
    }
    @Test void zeroDisablesTheGate() {
        ActionCooldowns gate = new ActionCooldowns();
        UUID player = UUID.randomUUID();
        assertEquals(0, gate.acquire(player, 0, 1));
        assertEquals(0, gate.acquire(player, 0, 1));
    }
}
