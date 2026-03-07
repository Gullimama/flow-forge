package com.flowforge.common.entity;

import com.flowforge.common.model.SnapshotMetadata;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "snapshots")
public class SnapshotEntity {

    @Id
    @Column(name = "snapshot_id")
    private UUID snapshotId;

    private String repoUrl;
    private String branch;
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_type")
    private SnapshotMetadata.SnapshotType snapshotType;

    @Column(name = "parent_snapshot")
    private UUID parentSnapshot;

    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changed_files")
    private List<String> changedFiles;

    @Enumerated(EnumType.STRING)
    private SnapshotStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata;

    public UUID getSnapshotId() { return snapshotId; }
    public void setSnapshotId(UUID snapshotId) { this.snapshotId = snapshotId; }
    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
    public SnapshotMetadata.SnapshotType getSnapshotType() { return snapshotType; }
    public void setSnapshotType(SnapshotMetadata.SnapshotType snapshotType) { this.snapshotType = snapshotType; }
    public UUID getParentSnapshot() { return parentSnapshot; }
    public void setParentSnapshot(UUID parentSnapshot) { this.parentSnapshot = parentSnapshot; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<String> getChangedFiles() { return changedFiles; }
    public void setChangedFiles(List<String> changedFiles) { this.changedFiles = changedFiles; }
    public SnapshotStatus getStatus() { return status; }
    public void setStatus(SnapshotStatus status) { this.status = status; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
