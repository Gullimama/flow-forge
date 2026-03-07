package com.flowforge.common.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SnapshotMetadata(
    UUID snapshotId,
    String repoUrl,
    String branch,
    String commitSha,
    SnapshotType snapshotType,
    Instant createdAt,
    List<String> changedFiles
) {
    public enum SnapshotType { BASELINE, REFRESH }
}
