package com.flowforge.ingest.github;

import com.flowforge.ingest.github.FileClassifier.FileType;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SnapshotResult(
    UUID snapshotId,
    String commitSha,
    int totalFiles,
    int javaFiles,
    int configFiles,
    int manifestFiles,
    List<String> detectedServices,
    Map<FileType, Integer> fileTypeCounts
) {}
