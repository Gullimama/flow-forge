package com.flowforge.api.dto;

import java.util.UUID;

public record ReindexRequest(
    UUID blobBatchId
) {}
