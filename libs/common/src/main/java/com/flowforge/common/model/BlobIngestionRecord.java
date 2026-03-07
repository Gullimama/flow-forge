package com.flowforge.common.model;

import java.time.Instant;
import java.util.UUID;

public record BlobIngestionRecord(
    UUID batchId,
    String storageAccount,
    String container,
    String prefix,
    String blobName,
    String etag,
    long contentLength,
    Instant lastModified
) {}
