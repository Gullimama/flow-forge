package com.flowforge.common.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "research_runs")
public class ResearchRunEntity {

    @Id
    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "snapshot_id", nullable = false)
    private UUID snapshotId;

    @Column(name = "blob_batch_id")
    private UUID blobBatchId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    private ResearchRunStatus status;

    private Instant createdAt;
    private Instant completedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "models_manifest")
    private Map<String, Object> modelsManifest;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pipeline_config")
    private Map<String, Object> pipelineConfig;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quality_metrics")
    private Map<String, Object> qualityMetrics;

    @Column(name = "output_path")
    private String outputPath;

    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public UUID getSnapshotId() { return snapshotId; }
    public void setSnapshotId(UUID snapshotId) { this.snapshotId = snapshotId; }
    public UUID getBlobBatchId() { return blobBatchId; }
    public void setBlobBatchId(UUID blobBatchId) { this.blobBatchId = blobBatchId; }
    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }
    public ResearchRunStatus getStatus() { return status; }
    public void setStatus(ResearchRunStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Map<String, Object> getModelsManifest() { return modelsManifest; }
    public void setModelsManifest(Map<String, Object> modelsManifest) { this.modelsManifest = modelsManifest; }
    public Map<String, Object> getPipelineConfig() { return pipelineConfig; }
    public void setPipelineConfig(Map<String, Object> pipelineConfig) { this.pipelineConfig = pipelineConfig; }
    public Map<String, Object> getQualityMetrics() { return qualityMetrics; }
    public void setQualityMetrics(Map<String, Object> qualityMetrics) { this.qualityMetrics = qualityMetrics; }
    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String outputPath) { this.outputPath = outputPath; }
}
