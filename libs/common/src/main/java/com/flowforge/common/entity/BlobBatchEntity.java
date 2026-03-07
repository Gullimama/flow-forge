package com.flowforge.common.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "blob_ingestion_batches")
public class BlobBatchEntity {

    @Id
    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "storage_account")
    private String storageAccount;

    private String container;
    private String prefix;

    @Enumerated(EnumType.STRING)
    private BatchMode mode;

    private Instant createdAt;
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    private BlobBatchStatus status;

    @Column(name = "blob_count")
    private Integer blobCount;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata;

    public UUID getBatchId() { return batchId; }
    public void setBatchId(UUID batchId) { this.batchId = batchId; }
    public String getStorageAccount() { return storageAccount; }
    public void setStorageAccount(String storageAccount) { this.storageAccount = storageAccount; }
    public String getContainer() { return container; }
    public void setContainer(String container) { this.container = container; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public BatchMode getMode() { return mode; }
    public void setMode(BatchMode mode) { this.mode = mode; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public BlobBatchStatus getStatus() { return status; }
    public void setStatus(BlobBatchStatus status) { this.status = status; }
    public Integer getBlobCount() { return blobCount; }
    public void setBlobCount(Integer blobCount) { this.blobCount = blobCount; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
