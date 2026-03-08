package com.flowforge.ingest.blob;

/**
 * Configuration for a blob ingestion run: Azure storage account, container, and optional prefix.
 */
public record BlobIngestionConfig(
    String storageAccount,
    String container,
    String prefix
) {
    public BlobIngestionConfig(String container, String prefix) {
        this("", container, prefix);
    }

    public BlobIngestionConfig(String container) {
        this("", container, "");
    }

    public String prefixOrEmpty() {
        return prefix != null ? prefix : "";
    }
}
