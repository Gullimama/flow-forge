package com.flowforge.ingest.blob;

import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobProperties;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.entity.BatchMode;
import com.flowforge.common.entity.LogType;
import com.flowforge.common.model.BlobBatchConfig;
import com.flowforge.common.model.BlobIngestionRecord;
import com.flowforge.common.service.MetadataService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * Full and incremental blob log ingestion: list Azure blobs, download, extract, upload to MinIO, track in PostgreSQL.
 */
@Service
@ConditionalOnBean(AzureBlobClient.class)
public class BlobIngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(BlobIngestionWorker.class);
    private static final String RAW_LOGS_BUCKET = "raw-logs";

    private final AzureBlobClient azureBlobClient;
    private final ZipExtractor zipExtractor;
    private final MinioStorageClient minioStorage;
    private final MetadataService metadataService;

    public BlobIngestionWorker(
            AzureBlobClient azureBlobClient,
            ZipExtractor zipExtractor,
            MinioStorageClient minioStorage,
            MetadataService metadataService) {
        this.azureBlobClient = azureBlobClient;
        this.zipExtractor = zipExtractor;
        this.minioStorage = minioStorage;
        this.metadataService = metadataService;
    }

    /**
     * Full ingestion: list all blobs, create batch, download/extract/upload each (skip if etag already seen).
     */
    public BatchIngestionResult executeFull(UUID jobId, BlobIngestionConfig config) throws IOException {
        String container = config.container();
        String prefix = config.prefixOrEmpty();
        String storageAccount = config.storageAccount() != null ? config.storageAccount() : "";

        BlobBatchConfig batchConfig = new BlobBatchConfig(storageAccount, container, prefix, BatchMode.FULL);
        UUID batchId = metadataService.createBlobBatch(batchConfig);

        List<BlobItem> items = azureBlobClient.listBlobs(container, prefix);
        int totalBlobs = items.size();
        int downloadedBlobs = 0;
        int skippedBlobs = 0;
        int failedBlobs = 0;
        long totalBytesDownloaded = 0;
        Map<LogType, Integer> logTypeCounts = new EnumMap<>(LogType.class);
        for (LogType t : LogType.values()) {
            logTypeCounts.put(t, 0);
        }

        Path tempDir = Files.createTempDirectory("blob-ingest-" + batchId);
        try {
            for (BlobItem item : items) {
                String blobName = item.getName();
                BlobProperties props = azureBlobClient.getBlobProperties(container, blobName);
                if (props == null) {
                    failedBlobs++;
                    continue;
                }
                String etag = props.getETag();
                long size = props.getBlobSize();
                Instant lastModified = props.getLastModified() != null ? props.getLastModified().toInstant() : Instant.now();

                if (metadataService.existsBlobByEtag(etag)) {
                    skippedBlobs++;
                    continue;
                }

                try {
                    metadataService.recordBlob(batchId, new BlobIngestionRecord(
                        batchId, storageAccount, container, prefix, blobName, etag, size, lastModified));

                    Path downloaded = azureBlobClient.downloadBlob(container, blobName, tempDir);
                    totalBytesDownloaded += Files.size(downloaded);

                    String archiveKey = batchId + "/" + blobName + "/archive.zip";
                    minioStorage.putObject(RAW_LOGS_BUCKET, archiveKey, Files.readAllBytes(downloaded), "application/zip");

                    Path extractDir = tempDir.resolve(blobName.replace("/", "_") + "_extract");
                    Files.createDirectories(extractDir);
                    List<ZipExtractor.ClassifiedLogFile> classified = zipExtractor.extractAndClassify(downloaded, extractDir);

                    String extractedPrefix = batchId + "/" + blobName + "/extracted/";
                    LogType dominantLogType = LogType.UNKNOWN;
                    for (ZipExtractor.ClassifiedLogFile cf : classified) {
                        String key = extractedPrefix + cf.path().getFileName().toString();
                        minioStorage.putObject(RAW_LOGS_BUCKET, key, Files.readAllBytes(cf.path()), "application/octet-stream");
                        logTypeCounts.merge(cf.logType(), 1, Integer::sum);
                        if (dominantLogType == LogType.UNKNOWN && cf.logType() != LogType.UNKNOWN) {
                            dominantLogType = cf.logType();
                        }
                    }
                    if (dominantLogType == LogType.UNKNOWN && !classified.isEmpty()) {
                        dominantLogType = classified.get(0).logType();
                    }

                    metadataService.updateBlobRecordToExtracted(batchId, blobName, etag, dominantLogType);
                    downloadedBlobs++;

                    Files.deleteIfExists(downloaded);
                    if (Files.exists(extractDir)) {
                        for (var p : classified) {
                            Files.deleteIfExists(p.path());
                        }
                        Files.deleteIfExists(extractDir);
                    }
                } catch (Exception e) {
                    log.warn("Failed to process blob {}: {}", blobName, e.getMessage());
                    failedBlobs++;
                    metadataService.updateBlobRecordFailed(batchId, blobName, etag, e.getMessage());
                }
            }

            metadataService.completeBlobBatch(batchId);
        } catch (Exception e) {
            log.error("Blob batch failed: {}", e.getMessage());
            metadataService.failBlobBatch(batchId);
            throw e;
        } finally {
            if (Files.exists(tempDir)) {
                try {
                    Files.walk(tempDir).sorted((a, b) -> -a.compareTo(b)).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                } catch (IOException ignored) {}
            }
        }

        return BatchIngestionResult.of(
            batchId, totalBlobs, downloadedBlobs, skippedBlobs, failedBlobs, totalBytesDownloaded, logTypeCounts);
    }

    /**
     * Incremental ingestion: only download blobs whose etag is not already in blob_records.
     */
    public BatchIngestionResult executeIncremental(UUID jobId, BlobIngestionConfig config) throws IOException {
        return executeFull(jobId, config);
    }
}
