package com.flowforge.common.model;

import com.flowforge.common.entity.BatchMode;

public record BlobBatchConfig(
    String storageAccount,
    String container,
    String prefix,
    BatchMode mode
) {
    public BlobBatchConfig(String storageAccount, String container, String prefix) {
        this(storageAccount, container, prefix, BatchMode.FULL);
    }
}
