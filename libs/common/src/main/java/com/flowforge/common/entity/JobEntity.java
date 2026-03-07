package com.flowforge.common.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class JobEntity {

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "job_type")
    private String jobType;

    @Column(name = "snapshot_id")
    private UUID snapshotId;

    @Column(name = "parent_job")
    private UUID parentJob;

    @Enumerated(EnumType.STRING)
    private JobStatusEnum status;

    @Column(name = "progress_pct")
    private float progressPct;

    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_params")
    private Map<String, Object> inputParams;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_refs")
    private Map<String, Object> outputRefs;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata;

    @Version
    private long version;

    public JobEntity() {
        this.version = 0L;
    }

    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }
    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }
    public UUID getSnapshotId() { return snapshotId; }
    public void setSnapshotId(UUID snapshotId) { this.snapshotId = snapshotId; }
    public UUID getParentJob() { return parentJob; }
    public void setParentJob(UUID parentJob) { this.parentJob = parentJob; }
    public JobStatusEnum getStatus() { return status; }
    public void setStatus(JobStatusEnum status) { this.status = status; }
    public float getProgressPct() { return progressPct; }
    public void setProgressPct(float progressPct) { this.progressPct = progressPct; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Map<String, Object> getInputParams() { return inputParams; }
    public void setInputParams(Map<String, Object> inputParams) { this.inputParams = inputParams; }
    public Map<String, Object> getOutputRefs() { return outputRefs; }
    public void setOutputRefs(Map<String, Object> outputRefs) { this.outputRefs = outputRefs; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
