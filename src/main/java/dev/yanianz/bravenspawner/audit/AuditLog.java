package dev.yanianz.bravenspawner.audit;

import dev.yanianz.bravenspawner.BravenSpawner;
import dev.yanianz.bravenspawner.config.Settings;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Append-only audit writer gated by {@code enable-logger}.
 *
 * <p>Audit events originate on region threads and must never block gameplay on disk
 * I/O. {@link #record(AuditEvent)} therefore only formats one line and offers it to a
 * bounded queue; a repeating async task owns the file handle, appends the batch and
 * flushes every line. The queue is capped at {@value #MAX_PENDING} entries: when a
 * broken disk stops draining, the writer drops new events and warns once instead of
 * growing without limit until the JVM runs out of heap.
 *
 * <p>The file is one per UTC+local date, named {@code audit-YYYY-MM-DD.log}. The date
 * is taken from the event timestamp, not from drain time, so a backlog flushed after
 * midnight still lands in the day the action happened.
 *
 * <p>The recent menu history stays in bounded memory even when disk logging is
 * disabled. {@link #enabled()} reads the live settings and gates disk enqueueing.
 */
public final class AuditLog implements AutoCloseable {

    /** Hard ceiling on queued events; beyond this, new events are dropped. */
    public static final int MAX_PENDING = 50_000;

    /** Cap per drain pass so one huge backlog cannot monopolise the async thread. */
    private static final int MAX_DRAIN_PER_PASS = 2_048;

    private static final long DRAIN_PERIOD_SECONDS = 1L;

    /** Shutdown waits this long for the final flush before reporting unsaved events. */
    private static final long CLOSE_WAIT_SECONDS = 3L;

    private static final String FILE_PREFIX = "audit-";
    private static final String FILE_SUFFIX = ".log";

    private record Pending(LocalDate date, String line) {}

    private final BravenSpawner plugin;
    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(MAX_PENDING);
    private final AtomicBoolean overflowWarned = new AtomicBoolean();
    private final AtomicBoolean writeWarned = new AtomicBoolean();
    private final RecentAuditEvents recent = new RecentAuditEvents(512, 64);

    /** Guards the open writer; only the drain path and {@link #close()} take it. */
    private final Object ioLock = new Object();

    private volatile ScheduledTask task;
    private volatile boolean started;
    private volatile boolean closed;

    private LocalDate openDate;
    private BufferedWriter writer;

    /**
     * Creates the writer and starts its async drain.
     *
     * <p>Starting in the constructor matches the other install-once services in the
     * plugin and removes a wiring step that is easy to forget: a logger that is
     * constructed but never started would accept events until the queue cap and then
     * drop them, which looks exactly like a broken audit trail.
     */
    public AuditLog(BravenSpawner plugin) {
        this.plugin = plugin;
        start();
    }

    /** True while {@code enable-logger: true}; controls disk logging only. */
    public boolean enabled() {
        Settings settings = plugin.settings();
        return settings != null && settings.enableLogger;
    }

    /**
     * Starts the async drain. Idempotent and a no-op after {@link #close()}, so a
     * reload path may call it blindly.
     */
    public synchronized void start() {
        if (started || closed) return;
        started = true;
        try {
            task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> drain(),
                    DRAIN_PERIOD_SECONDS, DRAIN_PERIOD_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            started = false;
            plugin.getLogger().severe("audit log drain could not start: " + e.getMessage());
        }
    }

    /**
     * Remembers an event for menus, then queues it when disk logging is enabled.
     * A full disk queue does not remove the event from recent memory history.
     */
    public void record(AuditEvent event) {
        if (event == null || closed) return;
        synchronized (recent) {
            if (closed) return;
            recent.record(event);
        }
        if (!enabled()) return;

        LocalDate date;
        try {
            date = Instant.ofEpochMilli(event.timestamp())
                    .atZone(ZoneId.systemDefault()).toLocalDate();
        } catch (RuntimeException e) {
            // A timestamp outside LocalDate's range cannot name a file; dropping the
            // record is safer than throwing inside a listener that just mutated state.
            return;
        }

        if (queue.offer(new Pending(date, format(event)))) return;
        if (overflowWarned.compareAndSet(false, true)) {
            plugin.getLogger().warning("audit log queue is full (" + MAX_PENDING
                    + " pending events); dropping new events until the drain catches up");
        }
    }

    /** Number of events waiting for the writer; diagnostics only. */
    public int pending() {
        return queue.size();
    }

    /** Immutable newest-first menu history; never reads or waits for disk. */
    public List<AuditEvent> recent(UUID spawnerId, int limit) {
        synchronized (recent) {
            return closed ? List.of() : recent.recent(spawnerId, limit);
        }
    }

    /**
     * Cancels the drain, flushes what is queued on the async scheduler and closes
     * the file.
     *
     * <p>The final flush deliberately runs on the async scheduler rather than the
     * calling thread: {@code onDisable} runs on the server thread, and even shutdown
     * must not put arbitrary disk latency in front of every other plugin's teardown.
     * If the scheduler will not start the flush, the queued events are reported as
     * lost instead of being written from a thread that never owned the writer.
     */
    @Override
    public void close() {
        ScheduledTask running;
        synchronized (this) {
            if (closed) return;
            closed = true;
            running = task;
            task = null;
        }
        if (running != null && !running.isCancelled()) running.cancel();

        synchronized (recent) {
            recent.clear();
        }

        flushOnShutdown();
        synchronized (ioLock) {
            closeWriterQuietly();
        }
    }

    private void flushOnShutdown() {
        if (queue.isEmpty() && writer == null) return;
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> {
                try {
                    drainRemaining();
                } finally {
                    latch.countDown();
                }
            });
            if (!latch.await(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("audit log did not finish flushing within "
                        + CLOSE_WAIT_SECONDS + "s; " + queue.size() + " event(s) may be lost");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("audit log flush interrupted; " + queue.size()
                    + " event(s) may be lost");
        } catch (RuntimeException e) {
            plugin.getLogger().warning("audit log flush could not be scheduled: "
                    + e.getMessage() + "; " + queue.size() + " event(s) may be lost");
        }
    }

    private synchronized void drain() {
        List<Pending> batch = new ArrayList<>(MAX_DRAIN_PER_PASS);
        queue.drainTo(batch, MAX_DRAIN_PER_PASS);
        if (!batch.isEmpty()) writeBatch(batch);
    }

    private synchronized void drainRemaining() {
        List<Pending> batch = new ArrayList<>(MAX_DRAIN_PER_PASS);
        while (!queue.isEmpty()) {
            queue.drainTo(batch, MAX_DRAIN_PER_PASS);
            if (batch.isEmpty()) break;
            writeBatch(batch);
            batch.clear();
        }
    }

    private void writeBatch(List<Pending> batch) {
        synchronized (ioLock) {
            for (int i = 0; i < batch.size(); i++) {
                try {
                    write(batch.get(i));
                } catch (IOException e) {
                    reportWriteFailure(e, batch.size() - i);
                    closeWriterQuietly();
                    return;
                }
            }
        }
    }

    /**
     * Appends one line and flushes it. The flush is per event, not per batch: an
     * audit trail that loses the last actions in a crash is worth very little, and
     * audit volume is low enough that the extra syscall is not measurable.
     */
    private void write(Pending pending) throws IOException {
        if (writer == null || !pending.date().equals(openDate)) {
            closeWriterQuietly();
            File directory = logDirectory(plugin);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("could not create " + directory);
            }
            File target = new File(directory, fileName(pending.date()));
            writer = new BufferedWriter(new FileWriter(target, java.nio.charset.StandardCharsets.UTF_8, true));
            openDate = pending.date();
        }
        writer.write(pending.line());
        writer.newLine();
        writer.flush();
    }

    private void reportWriteFailure(IOException error, int dropped) {
        if (writeWarned.compareAndSet(false, true)) {
            plugin.getLogger().warning("audit log write failed: " + error.getMessage()
                    + " (" + dropped + " event(s) dropped from this batch)");
        }
    }

    private void closeWriterQuietly() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException ignored) {
            // The stream is already unusable; closing again on the next pass is the
            // only recovery available and must not surface as a second exception.
        } finally {
            writer = null;
            openDate = null;
        }
    }

    /** Directory that holds every audit file; shared with {@link AuditMenu}. */
    static File logDirectory(BravenSpawner plugin) {
        return new File(plugin.getDataFolder(), "logs");
    }

    /** File name for a date; ISO ordering makes lexicographic sort chronological. */
    static String fileName(LocalDate date) {
        return FILE_PREFIX + date + FILE_SUFFIX;
    }

    private static String format(AuditEvent event) {
        return event.timestamp() + "|"
                + event.action().name() + "|"
                + event.actor() + "|"
                + clean(event.actorName()) + "|"
                + event.spawnerId() + "|"
                + clean(event.world()) + "|"
                + event.x() + "|"
                + event.y() + "|"
                + event.z() + "|"
                + event.amount() + "|"
                + clean(event.detail());
    }

    /**
     * The line format uses {@code |} as its separator, so any pipe inside a field
     * would shift every following column. Newlines were already flattened by
     * {@link AuditEvent}; this is the writer's last line of defence.
     */
    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('|', '/').replace('\r', ' ').replace('\n', ' ');
    }
}
