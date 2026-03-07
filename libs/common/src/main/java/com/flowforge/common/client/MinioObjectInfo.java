package com.flowforge.common.client;

import java.time.Instant;

public record MinioObjectInfo(
    String bucket,
    String key,
    long size,
    Instant lastModified,
    String etag
) {}
