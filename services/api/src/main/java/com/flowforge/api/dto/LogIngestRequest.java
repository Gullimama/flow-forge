package com.flowforge.api.dto;

import jakarta.validation.constraints.NotNull;

public record LogIngestRequest(
    String storageAccount,
    String container,
    String prefix,
    @NotNull IngestionMode mode
) {
    public enum IngestionMode { FULL, INCREMENTAL }
}
