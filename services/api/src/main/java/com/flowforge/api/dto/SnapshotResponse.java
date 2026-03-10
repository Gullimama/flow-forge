package com.flowforge.api.dto;

import java.time.Instant;
import java.util.UUID;

public record SnapshotResponse(
    UUID snapshotId,
    String repoUrl,
    String branch,
    String commitSha,
    String status,
    Instant createdAt
) {}
