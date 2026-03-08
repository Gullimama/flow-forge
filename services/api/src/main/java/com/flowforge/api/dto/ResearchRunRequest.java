package com.flowforge.api.dto;

import java.util.UUID;

public record ResearchRunRequest(
    UUID snapshotId,
    UUID blobBatchId
) {}
