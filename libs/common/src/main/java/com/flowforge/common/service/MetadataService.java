package com.flowforge.common.service;

import com.flowforge.common.entity.*;
import com.flowforge.common.model.BlobBatchConfig;
import com.flowforge.common.model.BlobIngestionRecord;
import com.flowforge.common.model.SnapshotMetadata;
import com.flowforge.common.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class MetadataService {

    private final SnapshotRepository snapshotRepo;
    private final JobRepository jobRepo;
    private final BlobBatchRepository blobBatchRepo;
    private final BlobRecordRepository blobRecordRepo;
    private final ResearchRunRepository researchRunRepo;
    private final ParseArtifactRepository parseArtifactRepo;

    public MetadataService(
            SnapshotRepository snapshotRepo,
            JobRepository jobRepo,
            BlobBatchRepository blobBatchRepo,
            BlobRecordRepository blobRecordRepo,
            ResearchRunRepository researchRunRepo,
            ParseArtifactRepository parseArtifactRepo) {
        this.snapshotRepo = snapshotRepo;
        this.jobRepo = jobRepo;
        this.blobBatchRepo = blobBatchRepo;
        this.blobRecordRepo = blobRecordRepo;
        this.researchRunRepo = researchRunRepo;
        this.parseArtifactRepo = parseArtifactRepo;
    }

    public UUID createSnapshot(SnapshotMetadata meta) {
        SnapshotEntity entity = new SnapshotEntity();
        entity.setSnapshotId(meta.snapshotId());
        entity.setRepoUrl(meta.repoUrl());
        entity.setBranch(meta.branch());
        entity.setCommitSha(meta.commitSha());
        entity.setSnapshotType(meta.snapshotType());
        entity.setCreatedAt(meta.createdAt());
        entity.setChangedFiles(meta.changedFiles() != null ? meta.changedFiles() : List.of());
        entity.setStatus(SnapshotStatus.PENDING);
        entity.setMetadata(Map.of());
        return snapshotRepo.save(entity).getSnapshotId();
    }

    public Optional<SnapshotEntity> getSnapshot(UUID id) {
        return snapshotRepo.findById(id);
    }

    public Optional<SnapshotEntity> getLatestSnapshot() {
        return snapshotRepo.findTopByOrderByCreatedAtDesc();
    }

    public void updateSnapshotStatus(UUID id, SnapshotStatus status) {
        snapshotRepo.findById(id).ifPresent(e -> {
            e.setStatus(status);
            snapshotRepo.save(e);
        });
    }

    public void updateSnapshotParent(UUID snapshotId, UUID parentSnapshotId) {
        snapshotRepo.findById(snapshotId).ifPresent(e -> {
            e.setParentSnapshot(parentSnapshotId);
            snapshotRepo.save(e);
        });
    }

    public UUID createJob(String jobType, Map<String, Object> params) {
        return createJob(jobType, null, params);
    }

    public UUID createJob(String jobType, UUID snapshotId, Map<String, Object> params) {
        JobEntity job = new JobEntity();
        job.setJobId(UUID.randomUUID());
        job.setJobType(jobType);
        job.setSnapshotId(snapshotId);
        job.setStatus(JobStatusEnum.PENDING);
        job.setProgressPct(0f);
        job.setCreatedAt(Instant.now());
        job.setInputParams(params != null ? params : Map.of());
        job.setOutputRefs(Map.of());
        job.setMetadata(Map.of());
        return jobRepo.save(job).getJobId();
    }

    public void updateJobStatus(UUID jobId, JobStatusEnum status, float progress) {
        jobRepo.updateStatus(jobId, status, progress);
    }

    public Optional<JobEntity> getJob(UUID jobId) {
        return jobRepo.findById(jobId);
    }

    public List<JobEntity> getJobsBySnapshot(UUID snapshotId) {
        return jobRepo.findBySnapshotId(snapshotId);
    }

    public UUID createBlobBatch(BlobBatchConfig config) {
        BlobBatchEntity batch = new BlobBatchEntity();
        batch.setBatchId(UUID.randomUUID());
        batch.setStorageAccount(config.storageAccount());
        batch.setContainer(config.container());
        batch.setPrefix(config.prefix() != null ? config.prefix() : "");
        batch.setMode(config.mode());
        batch.setCreatedAt(Instant.now());
        batch.setStatus(BlobBatchStatus.PENDING);
        batch.setBlobCount(0);
        batch.setMetadata(Map.of());
        return blobBatchRepo.save(batch).getBatchId();
    }

    public void recordBlob(UUID batchId, BlobIngestionRecord record) {
        BlobRecordEntity entity = new BlobRecordEntity();
        entity.setBatchId(batchId);
        entity.setBlobName(record.blobName());
        entity.setEtag(record.etag());
        entity.setContentLength(record.contentLength());
        entity.setLastModified(record.lastModified());
        entity.setStatus(BlobRecordStatus.PENDING);
        entity.setCreatedAt(Instant.now());
        blobRecordRepo.save(entity);
        blobBatchRepo.findById(batchId).ifPresent(b -> {
            b.setBlobCount((b.getBlobCount() == null ? 0 : b.getBlobCount()) + 1);
            blobBatchRepo.save(b);
        });
    }

    public List<BlobRecordEntity> getUnprocessedBlobs(UUID batchId) {
        return blobRecordRepo.findByBatchIdAndStatus(batchId, BlobRecordStatus.PENDING);
    }

    public boolean existsBlobByEtag(String etag) {
        return blobRecordRepo.existsByEtag(etag);
    }

    public void updateBlobRecordToExtracted(UUID batchId, String blobName, String etag, LogType logType) {
        blobRecordRepo.findByBatchIdAndBlobNameAndEtag(batchId, blobName, etag).ifPresent(e -> {
            e.setStatus(BlobRecordStatus.EXTRACTED);
            e.setLogType(logType);
            blobRecordRepo.save(e);
        });
    }

    public void updateBlobRecordFailed(UUID batchId, String blobName, String etag, String errorMessage) {
        blobRecordRepo.findByBatchIdAndBlobNameAndEtag(batchId, blobName, etag).ifPresent(e -> {
            e.setStatus(BlobRecordStatus.FAILED);
            e.setErrorMessage(errorMessage);
            blobRecordRepo.save(e);
        });
    }

    public Optional<BlobBatchEntity> getLatestCompletedBlobBatch() {
        return blobBatchRepo.findTopByStatusOrderByCompletedAtDesc(BlobBatchStatus.COMPLETED);
    }

    public void completeBlobBatch(UUID batchId) {
        blobBatchRepo.findById(batchId).ifPresent(b -> {
            b.setStatus(BlobBatchStatus.COMPLETED);
            b.setCompletedAt(Instant.now());
            blobBatchRepo.save(b);
        });
    }

    public void failBlobBatch(UUID batchId) {
        blobBatchRepo.findById(batchId).ifPresent(b -> {
            b.setStatus(BlobBatchStatus.FAILED);
            b.setCompletedAt(Instant.now());
            blobBatchRepo.save(b);
        });
    }

    public UUID createResearchRun(UUID snapshotId, UUID blobBatchId) {
        UUID jobId = createJob("RESEARCH_RUN", snapshotId, Map.of());
        ResearchRunEntity run = new ResearchRunEntity();
        run.setRunId(UUID.randomUUID());
        run.setSnapshotId(snapshotId);
        run.setBlobBatchId(blobBatchId);
        run.setJobId(jobId);
        run.setStatus(ResearchRunStatus.PENDING);
        run.setCreatedAt(Instant.now());
        run.setModelsManifest(Map.of());
        run.setPipelineConfig(Map.of());
        run.setQualityMetrics(Map.of());
        return researchRunRepo.save(run).getRunId();
    }

    public Optional<ResearchRunEntity> getResearchRun(UUID runId) {
        return researchRunRepo.findById(runId);
    }

    public Optional<ResearchRunEntity> getLatestResearchRun() {
        return researchRunRepo.findTopByStatusOrderByCreatedAtDesc(ResearchRunStatus.COMPLETED);
    }

    public void upsertParseArtifact(ParseArtifactEntity artifact) {
        Optional<ParseArtifactEntity> existing = parseArtifactRepo.findBySnapshotIdAndArtifactTypeAndArtifactKey(
                artifact.getSnapshotId(), artifact.getArtifactType(), artifact.getArtifactKey());
        if (existing.isPresent()) {
            ParseArtifactEntity e = existing.get();
            e.setContentHash(artifact.getContentHash());
            e.setMinioPath(artifact.getMinioPath());
            e.setStatus(artifact.getStatus());
            e.setMetadata(artifact.getMetadata());
            parseArtifactRepo.save(e);
        } else {
            if (artifact.getCreatedAt() == null) {
                artifact.setCreatedAt(Instant.now());
            }
            parseArtifactRepo.save(artifact);
        }
    }

    public List<String> getChangedArtifacts(UUID snapshotId, Map<String, String> hashes) {
        List<ParseArtifactEntity> artifacts = parseArtifactRepo.findBySnapshotId(snapshotId);
        List<String> changed = new ArrayList<>();
        for (ParseArtifactEntity a : artifacts) {
            String key = a.getArtifactKey();
            String currentHash = hashes.get(key);
            if (currentHash == null || !currentHash.equals(a.getContentHash())) {
                changed.add(key);
            }
        }
        for (String key : hashes.keySet()) {
            if (artifacts.stream().noneMatch(a -> a.getArtifactKey().equals(key))) {
                changed.add(key);
            }
        }
        return changed;
    }
}
