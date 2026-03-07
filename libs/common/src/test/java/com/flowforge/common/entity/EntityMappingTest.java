package com.flowforge.common.entity;

import com.flowforge.common.model.SnapshotMetadata;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class EntityMappingTest {

    @Test
    void snapshotEntityDefaultValues() {
        SnapshotEntity entity = new SnapshotEntity();
        entity.setSnapshotId(UUID.randomUUID());
        entity.setRepoUrl("https://github.com/org/repo");
        entity.setBranch("master");
        entity.setCommitSha("abc123");
        entity.setSnapshotType(SnapshotMetadata.SnapshotType.BASELINE);
        entity.setCreatedAt(Instant.now());
        entity.setStatus(SnapshotStatus.PENDING);

        assertThat(entity.getSnapshotId()).isNotNull();
        assertThat(entity.getSnapshotType()).isEqualTo(SnapshotMetadata.SnapshotType.BASELINE);
    }

    @Test
    void jobEntityVersionFieldStartsAtZero() {
        JobEntity entity = new JobEntity();
        assertThat(entity.getVersion()).isEqualTo(0L);
    }

    @Test
    void jobEntityStatusEnumValuesMatch() {
        assertThat(JobStatusEnum.values()).contains(
            JobStatusEnum.PENDING, JobStatusEnum.RUNNING, JobStatusEnum.COMPLETED,
            JobStatusEnum.FAILED, JobStatusEnum.CANCELLED
        );
    }
}
