package dev.yanianz.bravenspawner.command;
import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CommandPersistenceTest {
    @Test void corruptCommandMarksMutationForPersistenceWithoutWaitingForProduction() {
        BravenSpawner plugin = mock(BravenSpawner.class);
        SpawnerManager manager = mock(SpawnerManager.class);
        Player player = mock(Player.class);
        Command command = mock(Command.class);
        when(plugin.ready()).thenReturn(true);
        when(plugin.manager()).thenReturn(manager);
        when(player.hasPermission("bravenspawner.admin")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(command.getName()).thenReturn("bspawner");
        Spawner model = new Spawner(UUID.randomUUID(), player.getUniqueId(),"world",0,64,0,Spawner.Kind.MOB,"ZOMBIE",1,0,true);
        when(manager.ownedBy(player.getUniqueId())).thenReturn(List.of(model));
        new CommandHandler(plugin).onCommand(player, command,"bspawner",new String[]{"corrupt"});
        assertTrue(model.corrupted());
        verify(plugin).markDirty(model);
    }
}
