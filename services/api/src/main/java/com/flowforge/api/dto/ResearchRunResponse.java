package com.flowforge.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ResearchRunResponse(
    UUID runId,
    UUID snapshotId,
    UUID blobBatchId,
    String status,
    Instant createdAt,
    Instant completedAt,
    Map<String, Object> qualityMetrics,
    String outputPath
) {}
