package dev.yanianz.bravenspawner.storage;

import dev.yanianz.bravenspawner.model.Spawner;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** VSS-05: sales wait for a durable save; concurrent requests share one flush and failures are reported. */
class PersistSoonTest {
    final List<Consumer<ScheduledTask>> queued = new ArrayList<>();
    final List<Long> delayed = new ArrayList<>();
    final Plugin plugin = mock(Plugin.class);
    final Storage storage = mock(Storage.class);
    MockedStatic<Bukkit> bukkit;
    CheckpointService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        AsyncScheduler async = mock(AsyncScheduler.class);
        bukkit.when(Bukkit::getAsyncScheduler).thenReturn(async);
        when(async.runNow(any(), any())).thenAnswer(call -> {
            queued.add(call.getArgument(1));
            return mock(ScheduledTask.class);
        });
        when(async.runDelayed(any(), any(), anyLong(), any())).thenAnswer(call -> {
            delayed.add(call.getArgument(2));
            queued.add(call.getArgument(1));
            return mock(ScheduledTask.class);
        });
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PersistSoonTest"));
        when(storage.isHealthy()).thenReturn(true);
        service = new CheckpointService(plugin, storage, List::<Spawner>of, 60);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private void runQueued() {
        List<Consumer<ScheduledTask>> batch = new ArrayList<>(queued);
        queued.clear();
        batch.forEach(task -> task.accept(mock(ScheduledTask.class)));
    }

    @Test void concurrentRequestsShareOneFlush() {
        var first = service.persistSoon();
        var second = service.persistSoon();
        assertEquals(1, queued.size(), "one flush is scheduled for both");
        assertFalse(first.isDone());
        runQueued();
        assertTrue(first.join());
        assertTrue(second.join());
        verify(storage, times(1)).flushAll(anyList(), eq(false));
    }

    @Test void aRequestAfterAFlushStartedGetsItsOwnFlush() {
        var first = service.persistSoon();
        runQueued();
        var second = service.persistSoon();
        assertEquals(1, queued.size());
        assertEquals(1, delayed.size(), "a request right after a flush waits for the spacing");
        assertTrue(delayed.getFirst() > 0 && delayed.getFirst() <= 250);
        runQueued();
        assertTrue(first.join() && second.join());
        verify(storage, times(2)).flushAll(anyList(), eq(false));
    }

    @Test void failedFlushOrUnhealthyStorageReportsFalse() {
        doThrow(new Storage.StorageException("disk full")).when(storage).flushAll(anyList(), anyBoolean());
        var failed = service.persistSoon();
        runQueued();
        assertFalse(failed.join());

        when(storage.isHealthy()).thenReturn(false);
        var unhealthy = service.persistSoon();
        runQueued();
        assertFalse(unhealthy.join());
    }

    @Test void closingFailsPendingRequests() {
        var pending = service.persistSoon();
        service.close();
        assertFalse(pending.join());
        assertFalse(service.persistSoon().join());
    }
}
