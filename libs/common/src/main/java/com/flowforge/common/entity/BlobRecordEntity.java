package com.flowforge.common.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "blob_records")
public class BlobRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "blob_name", nullable = false)
    private String blobName;

    private String etag;

    @Column(name = "content_length", nullable = false)
    private long contentLength;

    @Column(name = "last_modified", nullable = false)
    private Instant lastModified;

    @Enumerated(EnumType.STRING)
    @Column(name = "log_type")
    private LogType logType;

    @Enumerated(EnumType.STRING)
    private BlobRecordStatus status;

    @Column(name = "error_message")
    private String errorMessage;

    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getBatchId() { return batchId; }
    public void setBatchId(UUID batchId) { this.batchId = batchId; }
    public String getBlobName() { return blobName; }
    public void setBlobName(String blobName) { this.blobName = blobName; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
    public long getContentLength() { return contentLength; }
    public void setContentLength(long contentLength) { this.contentLength = contentLength; }
    public Instant getLastModified() { return lastModified; }
    public void setLastModified(Instant lastModified) { this.lastModified = lastModified; }
    public LogType getLogType() { return logType; }
    public void setLogType(LogType logType) { this.logType = logType; }
    public BlobRecordStatus getStatus() { return status; }
    public void setStatus(BlobRecordStatus status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
