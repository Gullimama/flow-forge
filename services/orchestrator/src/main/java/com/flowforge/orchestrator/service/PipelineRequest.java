package com.flowforge.orchestrator.service;

import java.util.List;
import java.util.UUID;

public record PipelineRequest(
    UUID snapshotId,
    List<String> repoUrls,
    String logTimeRange,
    boolean runGnn
) {}

