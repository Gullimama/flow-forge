package com.flowforge.api.dto;

import java.time.Instant;
import java.util.UUID;

public record JobStatusResponse(
    UUID jobId,
    String jobType,
    String status,
    double progressPct,
    Instant createdAt,
    Instant startedAt,
    Instant completedAt,
    String errorMessage
) {}
