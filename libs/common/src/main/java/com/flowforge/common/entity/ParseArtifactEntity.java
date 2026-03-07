package com.flowforge.common.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "parse_artifacts")
public class ParseArtifactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_id", nullable = false)
    private UUID snapshotId;

    @Column(name = "artifact_type", nullable = false)
    private String artifactType;

    @Column(name = "artifact_key", nullable = false)
    private String artifactKey;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "minio_path", nullable = false)
    private String minioPath;

    @Enumerated(EnumType.STRING)
    private ParseArtifactStatus status;

    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getSnapshotId() { return snapshotId; }
    public void setSnapshotId(UUID snapshotId) { this.snapshotId = snapshotId; }
    public String getArtifactType() { return artifactType; }
    public void setArtifactType(String artifactType) { this.artifactType = artifactType; }
    public String getArtifactKey() { return artifactKey; }
    public void setArtifactKey(String artifactKey) { this.artifactKey = artifactKey; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getMinioPath() { return minioPath; }
    public void setMinioPath(String minioPath) { this.minioPath = minioPath; }
    public ParseArtifactStatus getStatus() { return status; }
    public void setStatus(ParseArtifactStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
