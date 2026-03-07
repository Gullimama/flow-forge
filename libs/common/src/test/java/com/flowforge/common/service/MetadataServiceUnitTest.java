package com.flowforge.common.service;

import com.flowforge.common.entity.*;
import com.flowforge.common.model.SnapshotMetadata;
import com.flowforge.common.repository.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class MetadataServiceUnitTest {

    @Mock private SnapshotRepository snapshotRepo;
    @Mock private JobRepository jobRepo;
    @Mock private BlobBatchRepository blobBatchRepo;
    @Mock private BlobRecordRepository blobRecordRepo;
    @Mock private ResearchRunRepository researchRunRepo;
    @Mock private ParseArtifactRepository parseArtifactRepo;

    @InjectMocks private MetadataService metadataService;

    @Test
    void createSnapshotPersistsEntityAndReturnsId() {
        SnapshotMetadata meta = new SnapshotMetadata(
            UUID.randomUUID(), "https://github.com/org/repo", "master",
            "sha-1", SnapshotMetadata.SnapshotType.BASELINE, Instant.now(), List.of()
        );
        when(snapshotRepo.save(any(SnapshotEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID id = metadataService.createSnapshot(meta);

        assertThat(id).isNotNull();
        verify(snapshotRepo).save(any(SnapshotEntity.class));
    }

    @Test
    void updateJobStatusDelegatesToRepository() {
        UUID jobId = UUID.randomUUID();

        metadataService.updateJobStatus(jobId, JobStatusEnum.RUNNING, 25.0f);

        verify(jobRepo).updateStatus(jobId, JobStatusEnum.RUNNING, 25.0f);
    }

    @Test
    void getUnprocessedBlobsFiltersCorrectly() {
        UUID batchId = UUID.randomUUID();
        BlobRecordEntity pending = new BlobRecordEntity();
        pending.setStatus(BlobRecordStatus.PENDING);
        when(blobRecordRepo.findByBatchIdAndStatus(batchId, BlobRecordStatus.PENDING))
            .thenReturn(List.of(pending));

        var result = metadataService.getUnprocessedBlobs(batchId);

        assertThat(result).hasSize(1);
    }

    @Test
    void upsertParseArtifactUpdatesExistingRecord() {
        ParseArtifactEntity existing = new ParseArtifactEntity();
        existing.setContentHash("old-hash");
        when(parseArtifactRepo.findBySnapshotIdAndArtifactTypeAndArtifactKey(
            any(), any(), any())).thenReturn(Optional.of(existing));

        ParseArtifactEntity artifact = new ParseArtifactEntity();
        artifact.setSnapshotId(UUID.randomUUID());
        artifact.setArtifactType("JAVA_CLASS");
        artifact.setArtifactKey("com.example.Foo");
        artifact.setContentHash("new-hash");

        metadataService.upsertParseArtifact(artifact);

        verify(parseArtifactRepo).save(any());
    }
}
