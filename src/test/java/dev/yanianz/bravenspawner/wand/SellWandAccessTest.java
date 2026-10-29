package dev.yanianz.bravenspawner.wand;

import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SellWandAccessTest {
    @Test void deniesStrangersButAllowsOwnerTrustAndAdmin() {
        UUID owner = UUID.randomUUID(), trusted = UUID.randomUUID(), stranger = UUID.randomUUID();
        Spawner spawner = new Spawner(UUID.randomUUID(), owner, "world", 0, 64, 0,
                Spawner.Kind.MOB, "PIG", 1, 0, true);
        spawner.trusted().add(trusted);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(stranger);
        assertFalse(SellWandService.canSell(player, spawner));
        when(player.getUniqueId()).thenReturn(owner);
        assertTrue(SellWandService.canSell(player, spawner));
        when(player.getUniqueId()).thenReturn(trusted);
        assertTrue(SellWandService.canSell(player, spawner));
        when(player.getUniqueId()).thenReturn(stranger);
        when(player.hasPermission("bravenspawner.admin")).thenReturn(true);
        assertTrue(SellWandService.canSell(player, spawner));
    }
}
