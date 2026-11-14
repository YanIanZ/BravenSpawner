package dev.yanianz.bravenspawner.convert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Scan receipt math: coverage, per-reason skips and per-chunk merges. */
class ScanReportTest {

    @Test void coverageCountsOnlyChunksActuallyRead() {
        ScanReport report = new ScanReport();
        report.setChunksTotal(4);
        report.chunkScanned();
        report.chunkScanned();
        assertEquals("2/4", report.coverage());
        assertEquals(2, report.chunksScanned());
        assertEquals(4, report.chunksTotal());
    }

    @Test void skipsGroupByReason() {
        ScanReport report = new ScanReport();
        report.skipped(ConvertCheck.NO_PRODUCT);
        report.skipped(ConvertCheck.NO_PRODUCT);
        report.skipped(ConvertCheck.CHUNK_CAP);
        report.skipped(ConvertCheck.CONVERTIBLE);
        report.skipped(ConvertCheck.ALREADY_MANAGED);
        report.skipped(null);
        assertEquals(2, report.skippedCount(ConvertCheck.NO_PRODUCT));
        assertEquals(1, report.skippedCount(ConvertCheck.CHUNK_CAP));
        assertEquals(0, report.skippedCount(ConvertCheck.THROTTLED));
        assertEquals(3, report.skippedTotal());
        assertEquals(2, report.skippedByReason().size());
    }

    @Test void mergeFoldsChunkReports() {
        ScanReport chunk = new ScanReport();
        chunk.chunkScanned();
        chunk.converted();
        chunk.alreadyManaged();
        chunk.skipped(ConvertCheck.THROTTLED);
        chunk.skippedWorld("skipped_nether");

        ScanReport total = new ScanReport();
        total.setChunksTotal(10);
        total.merge(chunk);
        total.merge(null);
        total.merge(total);
        assertEquals(1, total.chunksScanned());
        assertEquals(10, total.chunksTotal(), "merging never moves the denominator");
        assertEquals(1, total.convertedCount());
        assertEquals(1, total.alreadyManagedCount());
        assertEquals(1, total.skippedCount(ConvertCheck.THROTTLED));
        assertEquals(1, total.skippedTotal());
        assertEquals(java.util.List.of("skipped_nether"), total.skippedWorlds());
    }

    @Test void lastChunkClaimsTheReceiptExactlyOnce() {
        ScanReport report = new ScanReport();
        report.setChunksTotal(2);
        assertFalse(report.chunkDone());
        assertTrue(report.chunkDone());
        assertTrue(report.claimReceipt());
        assertFalse(report.claimReceipt(), "timeout and task must not double-send");
    }
}
