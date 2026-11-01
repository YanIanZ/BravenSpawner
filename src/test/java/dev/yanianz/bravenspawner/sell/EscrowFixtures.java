package dev.yanianz.bravenspawner.sell;

import dev.yanianz.bravenspawner.model.Escrow;
import dev.yanianz.bravenspawner.model.Spawner;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Shared test wiring for the escrowed sale flow. */
final class EscrowFixtures {
    private EscrowFixtures() {}

    /** A mock spawner whose hold/release/refund behave like the real model. */
    static void escrowable(Spawner spawner) {
        when(spawner.hold(any(), any(), any(), anyDouble(), anyList())).thenAnswer(call ->
                new Escrow(call.getArgument(0),
                        call.getArgument(1) == null ? new java.util.UUID(0, 1) : call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), 0L, call.getArgument(4)));
        when(spawner.release(any())).thenReturn(true);
        when(spawner.refund(any())).thenReturn(true);
    }

    /** Saves succeed immediately; settlement steps run inline on the calling thread. */
    static void inline(SellService service) {
        service.testHooks(new SellService.Dispatcher() {
            @Override public boolean onPlayer(Player player, Runnable task, Runnable retired) {
                task.run();
                return true;
            }
            @Override public boolean onGlobal(Runnable task) {
                task.run();
                return true;
            }
        }, () -> CompletableFuture.completedFuture(true));
    }

    /** Saves succeed immediately; thread dispatch stays real (Bukkit schedulers). */
    static void durable(SellService service) {
        service.testHooks(null, () -> CompletableFuture.completedFuture(true));
    }

    /** The spawner is registered and the selling player online, as on a live server. */
    static void live(dev.yanianz.bravenspawner.BravenSpawner plugin, Spawner spawner, Player player) {
        var manager = mock(dev.yanianz.bravenspawner.manager.SpawnerManager.class);
        when(plugin.manager()).thenReturn(manager);
        when(manager.byId(any())).thenReturn(spawner);
        when(player.isOnline()).thenReturn(true);
    }
}
