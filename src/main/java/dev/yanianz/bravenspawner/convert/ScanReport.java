package dev.yanianz.bravenspawner.convert;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts for one conversion pass: a single chunk, or a whole admin scan.
 *
 * <p>A scan dispatches one region task per loaded chunk, so the shared report
 * is written from many region threads at once; every mutator is synchronized.
 * Chunk tasks alternatively build a private report and {@link #merge} it once,
 * which keeps the same class on both paths.
 *
 * <p>Coverage is honest by construction: {@link #chunksTotal} is fixed when
 * the scan starts (loaded chunks only, never force-loaded) and
 * {@link #chunksScanned} only counts chunks a region task actually read. A
 * chunk that unloaded mid-scan lowers coverage instead of silently vanishing.
 */
public final class ScanReport {

    private final Object lock = new Object();
    private int worldsScanned;
    private final List<String> skippedWorlds = new ArrayList<>();
    private int chunksTotal;
    private int chunksScanned;
    private int converted;
    private int alreadyManaged;
    private final EnumMap<ConvertCheck, Integer> skipped = new EnumMap<>(ConvertCheck.class);

    private final AtomicInteger remaining = new AtomicInteger();
    private final AtomicBoolean receiptClaimed = new AtomicBoolean();

    /** Fixes the denominator: how many loaded chunks the scan will visit. */
    public void setChunksTotal(int total) {
        synchronized (lock) {
            this.chunksTotal = Math.max(0, total);
        }
        remaining.set(Math.max(0, total));
    }

    public void worldScanned() {
        synchronized (lock) {
            worldsScanned++;
        }
    }

    public void skippedWorld(String world) {
        if (world == null) {
            return;
        }
        synchronized (lock) {
            skippedWorlds.add(world);
        }
    }

    /** One chunk was actually read by its region task. */
    public void chunkScanned() {
        synchronized (lock) {
            chunksScanned++;
        }
    }

    /**
     * Marks one dispatched chunk finished (scanned or not) and reports whether
     * it was the last one, so exactly one task sends the receipt.
     */
    public boolean chunkDone() {
        return remaining.decrementAndGet() <= 0;
    }

    /** Exactly one caller (the last task or the timeout) wins the receipt. */
    public boolean claimReceipt() {
        return receiptClaimed.compareAndSet(false, true);
    }

    public void converted() {
        synchronized (lock) {
            converted++;
        }
    }

    public void alreadyManaged() {
        synchronized (lock) {
            alreadyManaged++;
        }
    }

    /** Counts one skip; convertible and already-managed verdicts never land here. */
    public void skipped(ConvertCheck reason) {
        if (reason == null || reason.convertible() || reason == ConvertCheck.ALREADY_MANAGED) {
            return;
        }
        synchronized (lock) {
            skipped.merge(reason, 1, Integer::sum);
        }
    }

    /** Folds a per-chunk report into this one. */
    public void merge(ScanReport other) {
        if (other == null || other == this) {
            return;
        }
        synchronized (other.lock) {
            synchronized (lock) {
                worldsScanned += other.worldsScanned;
                skippedWorlds.addAll(other.skippedWorlds);
                chunksScanned += other.chunksScanned;
                converted += other.converted;
                alreadyManaged += other.alreadyManaged;
                for (Map.Entry<ConvertCheck, Integer> entry : other.skipped.entrySet()) {
                    skipped.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
            }
        }
    }

    public int worldsScanned() {
        synchronized (lock) {
            return worldsScanned;
        }
    }

    public List<String> skippedWorlds() {
        synchronized (lock) {
            return List.copyOf(skippedWorlds);
        }
    }

    public int chunksTotal() {
        synchronized (lock) {
            return chunksTotal;
        }
    }

    public int chunksScanned() {
        synchronized (lock) {
            return chunksScanned;
        }
    }

    public int convertedCount() {
        synchronized (lock) {
            return converted;
        }
    }

    public int alreadyManagedCount() {
        synchronized (lock) {
            return alreadyManaged;
        }
    }

    public int skippedCount(ConvertCheck reason) {
        synchronized (lock) {
            return skipped.getOrDefault(reason, 0);
        }
    }

    public int skippedTotal() {
        synchronized (lock) {
            int total = 0;
            for (int count : skipped.values()) {
                total += count;
            }
            return total;
        }
    }

    public Map<ConvertCheck, Integer> skippedByReason() {
        synchronized (lock) {
            return Map.copyOf(skipped);
        }
    }

    /** {@code scanned/total} coverage text for the receipt. */
    public String coverage() {
        synchronized (lock) {
            return String.format(Locale.ROOT, "%,d/%,d", chunksScanned, chunksTotal);
        }
    }
}
