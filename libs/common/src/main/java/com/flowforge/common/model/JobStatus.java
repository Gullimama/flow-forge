package com.flowforge.common.model;

import java.time.Instant;
import java.util.UUID;

public record JobStatus(
    UUID jobId,
    String jobType,
    Status status,
    Instant createdAt,
    Instant updatedAt,
    String errorMessage,
    double progressPct
) {
    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }
}
