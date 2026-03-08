package com.flowforge.ingest.blob;

import com.flowforge.common.entity.LogType;
import java.util.Map;
import java.util.UUID;

/**
 * Result of a blob batch ingestion run.
 */
public record BatchIngestionResult(
    UUID batchId,
    int totalBlobs,
    int downloadedBlobs,
    int skippedBlobs,
    int failedBlobs,
    long totalBytesDownloaded,
    Map<LogType, Integer> logTypeCounts
) {
    public static BatchIngestionResult of(
            UUID batchId,
            int totalBlobs,
            int downloadedBlobs,
            int skippedBlobs,
            int failedBlobs,
            long totalBytesDownloaded,
            Map<LogType, Integer> logTypeCounts) {
        return new BatchIngestionResult(
            batchId,
            totalBlobs,
            downloadedBlobs,
            skippedBlobs,
            failedBlobs,
            totalBytesDownloaded,
            logTypeCounts != null ? logTypeCounts : Map.of());
    }
}
