package dev.yanianz.bravenspawner.storage;

import dev.yanianz.bravenspawner.model.Spawner;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Periodic durable checkpoint plus the bounded shutdown drain.
 *
 * <p>Spawner state mutates on region threads while the disk is written on an async
 * thread, so the backend is only durable at a checkpoint boundary. The service runs
 * every {@code storage.checkpoint-seconds} (default 60) and only calls the backend
 * when it reports a pending write; an idle server never rewrites its database.
 *
 * <p>Two invariants are enforced here:
 * <ul>
 *   <li>No flush while {@link Storage#isHealthy()} is false. A failed load leaves the
 *       in-memory map partial or empty, and flushing it would erase records that were
 *       never read.</li>
 *   <li>Shutdown never blocks indefinitely: the final drain runs on the async
 *       scheduler and is awaited for at most five seconds, reporting what was not
 *       confirmed instead of throwing into {@code onDisable}.</li>
 * </ul>
 */
public final class CheckpointService implements AutoCloseable {

    /** Hard ceiling on the shutdown drain from the technical spec. */
    public static final long DRAIN_TIMEOUT_MILLIS = 5_000L;

    private static final long DEFAULT_INTERVAL_SECONDS = 60L;
    private static final long MIN_INTERVAL_SECONDS = 1L;

    private final Plugin plugin;
    private final Storage storage;
    private final Supplier<Collection<Spawner>> liveSpawners;
    private final long intervalSeconds;
    private final Object flushLock = new Object();

    private volatile ScheduledTask task;
    private volatile boolean started;
    private volatile boolean closed;
    private volatile boolean unhealthyLogged;

    /**
     * @param liveSpawners supplier of the authoritative live records; evaluated on
     *                     each checkpoint so a deleted spawner is never resurrected
     */
    public CheckpointService(Plugin plugin,
                             Storage storage,
                             Supplier<Collection<Spawner>> liveSpawners) {
        this(plugin, storage, liveSpawners, -1L);
    }

    /**
     * Testing/override variant; a negative {@code intervalSeconds} reads
     * {@code storage.checkpoint-seconds} from the plugin config.
     */
    public CheckpointService(Plugin plugin,
                             Storage storage,
                             Supplier<Collection<Spawner>> liveSpawners,
                             long intervalSeconds) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.liveSpawners = Objects.requireNonNull(liveSpawners, "liveSpawners");
        this.intervalSeconds = intervalSeconds > 0
                ? Math.max(MIN_INTERVAL_SECONDS, intervalSeconds)
                : configuredInterval(plugin);
        start();
    }

    private static long configuredInterval(Plugin plugin) {
        long configured = plugin.getConfig().getLong("storage.checkpoint-seconds", DEFAULT_INTERVAL_SECONDS);
        return Math.max(MIN_INTERVAL_SECONDS, configured);
    }

    /** Starts the fixed-rate checkpoint; idempotent and a no-op after {@link #close()}. */
    public synchronized void start() {
        if (started || closed) return;
        started = true;
        try {
            task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> checkpoint(),
                    intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            started = false;
            plugin.getLogger().severe("checkpoint scheduler could not start: " + e.getMessage());
        }
    }

    /** One checkpoint pass; public so tests and reload paths can force the same code. */
    public void checkpoint() {
        if (closed) return;
        if (!storage.isHealthy()) {
            if (!unhealthyLogged) {
                unhealthyLogged = true;
                plugin.getLogger().warning("checkpoint paused: storage is unhealthy after a failed load");
            }
            return;
        }
        unhealthyLogged = false;
        if (!storage.isDirty()) return;
        List<Spawner> snapshot = snapshot();
        if (!flush(snapshot, false)) return;
        String done = "checkpoint flushed (" + snapshot.size() + " live spawner record(s))";
        if (plugin instanceof dev.yanianz.bravenspawner.BravenSpawner host) host.debug(done);
        else plugin.getLogger().fine(done);
    }

    /**
     * Flushes every live record regardless of the dirty flag (a full rewrite on
     * row-based backends), but never while unhealthy. Returns true when the backend
     * accepted the write.
     */
    public boolean flushNow() {
        if (!storage.isHealthy()) {
            plugin.getLogger().warning("manual flush refused: storage is unhealthy after a failed load");
            return false;
        }
        return flush(snapshot(), true);
    }

    /** Requests not yet covered by a flush; guarded by {@link #pendingLock}. */
    private final Object pendingLock = new Object();
    private List<CompletableFuture<Boolean>> pending = new ArrayList<>();
    private boolean persistScheduled;

    /**
     * Makes everything marked dirty so far durable, soon, off the calling thread.
     *
     * <p>The future completes {@code true} only after a flush that started after this
     * call has been accepted by the backend; {@code false} when storage is unhealthy,
     * the flush failed or the service is closed. Concurrent requests share one flush.
     * Sales use this to save their escrow before asking the economy to pay.
     */
    public CompletableFuture<Boolean> persistSoon() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (closed) {
            future.complete(false);
            return future;
        }
        boolean schedule;
        synchronized (pendingLock) {
            pending.add(future);
            schedule = !persistScheduled;
            persistScheduled = true;
        }
        if (schedule) {
            try {
                Bukkit.getAsyncScheduler().runNow(plugin, t -> persistPending());
            } catch (RuntimeException e) {
                plugin.getLogger().warning("could not schedule a durable save: " + e.getMessage());
                persistPending();
            }
        }
        return future;
    }

    private void persistPending() {
        List<CompletableFuture<Boolean>> batch;
        synchronized (pendingLock) {
            batch = pending;
            pending = new ArrayList<>();
            persistScheduled = false;
        }
        if (batch.isEmpty()) return;
        boolean saved;
        try {
            saved = !closed && storage.isHealthy() && flush(snapshot(), false);
        } catch (RuntimeException e) {
            saved = false;
        }
        for (CompletableFuture<Boolean> future : batch) future.complete(saved);
    }

    private List<Spawner> snapshot() {
        Collection<Spawner> live = liveSpawners.get();
        if (live == null || live.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(live);
    }

    private boolean flush(List<Spawner> snapshot, boolean full) {
        synchronized (flushLock) {
            try {
                storage.flushAll(snapshot, full);
                return true;
            } catch (RuntimeException e) {
                plugin.getLogger().severe("checkpoint flush failed; records stay dirty: " + Storage.describeFailure(e));
                return false;
            }
        }
    }

    /**
     * Stops the schedule and performs one bounded drain. Never throws: this runs
     * from {@code onDisable}, where an exception would abort the rest of shutdown.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // A save requested now would never run; tell its sale so it is returned.
        failPending();
        ScheduledTask current = task;
        task = null;
        if (current != null) {
            try {
                current.cancel();
            } catch (RuntimeException ignored) {
                // the scheduler may already be stopping
            }
        }
        drain();
    }

    private void failPending() {
        List<CompletableFuture<Boolean>> batch;
        synchronized (pendingLock) {
            batch = pending;
            pending = new ArrayList<>();
        }
        for (CompletableFuture<Boolean> future : batch) future.complete(false);
    }

    private void drain() {
        if (!storage.isHealthy()) {
            plugin.getLogger().warning("checkpoint drain skipped: storage is unhealthy after a failed load");
            return;
        }
        List<Spawner> snapshot = snapshot();
        if (!storage.isDirty()) {
            plugin.getLogger().info("checkpoint drain: no pending writes");
            return;
        }

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Runnable write = () -> {
            try {
                if (!flush(snapshot, false)) failure.set(new Storage.StorageException("checkpoint drain flush failed"));
            } catch (RuntimeException e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        };

        try {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> write.run());
        } catch (RuntimeException e) {
            // Scheduler already closed: write inline rather than lose the drain.
            write.run();
        }

        try {
            if (!done.await(DRAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                plugin.getLogger().severe("checkpoint drain timed out after " + DRAIN_TIMEOUT_MILLIS
                        + "ms; up to " + snapshot.size() + " record(s) may not be durable");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().severe("checkpoint drain interrupted; up to "
                    + snapshot.size() + " record(s) may not be durable");
            return;
        }

        RuntimeException error = failure.get();
        if (error != null) {
            plugin.getLogger().severe("checkpoint drain failed: " + error.getMessage()
                    + "; up to " + snapshot.size() + " record(s) may not be durable");
            return;
        }
        plugin.getLogger().info("checkpoint drain wrote " + snapshot.size() + " spawner record(s)");
    }
}
