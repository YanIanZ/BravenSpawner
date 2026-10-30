package dev.yanianz.bravenspawner.command;
import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.manager.SpawnerManager;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
        org.bukkit.block.Block block = mock(org.bukkit.block.Block.class);
        org.bukkit.Location location = mock(org.bukkit.Location.class);
        when(player.getTargetBlockExact(6)).thenReturn(block);
        when(block.getLocation()).thenReturn(location);
        when(manager.at(location)).thenReturn(model);
        new CommandHandler(plugin).onCommand(player, command,"bspawner",new String[]{"corrupt"});
        assertTrue(model.corrupted());
        verify(plugin).markDirty(model);
    }

    @Test void corruptTargetsTheLookedAtSpawnerNotAnArbitraryOwnedOne() {
        BravenSpawner plugin = mock(BravenSpawner.class);
        SpawnerManager manager = mock(SpawnerManager.class);
        Player player = mock(Player.class);
        Command command = mock(Command.class);
        when(plugin.ready()).thenReturn(true);
        when(plugin.manager()).thenReturn(manager);
        when(player.hasPermission("bravenspawner.admin")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(command.getName()).thenReturn("bspawner");
        Spawner owned = new Spawner(UUID.randomUUID(), player.getUniqueId(),"world",0,64,0,Spawner.Kind.MOB,"ZOMBIE",1,0,true);
        when(manager.ownedBy(player.getUniqueId())).thenReturn(java.util.List.of(owned));
        new CommandHandler(plugin).onCommand(player, command,"bspawner",new String[]{"corrupt"});
        assertFalse(owned.corrupted(), "nothing is looked at, so nothing may be corrupted");
        verify(player).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test void failedReloadIsReportedInsteadOfThrowing() {
        BravenSpawner plugin = mock(BravenSpawner.class);
        Player player = mock(Player.class);
        Command command = mock(Command.class);
        when(plugin.ready()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(player.hasPermission("bravenspawner.admin")).thenReturn(true);
        when(command.getName()).thenReturn("bspawner");
        doThrow(new IllegalStateException("Could not save spawner state before reload")).when(plugin).reloadAll();
        assertDoesNotThrow(() -> new CommandHandler(plugin).onCommand(player, command, "bspawner", new String[]{"reload"}));
        verify(player).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test void doctorIsInHelpAndTabCompletion() {
        BravenSpawner plugin = mock(BravenSpawner.class);
        Player player = mock(Player.class);
        Command command = mock(Command.class);
        when(player.hasPermission("bravenspawner.admin")).thenReturn(true);
        when(command.getName()).thenReturn("bspawner");
        assertTrue(new CommandHandler(plugin).onTabComplete(player, command, "bspawner", new String[]{"d"}).contains("doctor"));
        assertTrue(String.join("\n", dev.yanianz.bravenspawner.util.Messages.defaults().lines("help")).contains("doctor"));
    }
}
