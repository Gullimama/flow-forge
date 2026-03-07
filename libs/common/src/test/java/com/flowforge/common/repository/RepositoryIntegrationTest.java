package com.flowforge.common.repository;

import com.flowforge.common.TestJpaApplication;
import com.flowforge.common.entity.*;
import com.flowforge.common.model.SnapshotMetadata;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(classes = TestJpaApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class RepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("flowforge_test")
        .withUsername("flowforge")
        .withPassword("flowforge")
        .withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private SnapshotRepository snapshotRepo;
    @Autowired
    private JobRepository jobRepo;
    @Autowired
    private ParseArtifactRepository parseArtifactRepo;

    @Test
    void flywayMigrationsRunSuccessfully() {
        assertThat(snapshotRepo.count()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void snapshotCrudRoundTrip() {
        SnapshotEntity entity = new SnapshotEntity();
        entity.setSnapshotId(UUID.randomUUID());
        entity.setRepoUrl("https://github.com/org/repo");
        entity.setBranch("master");
        entity.setCommitSha("abc123");
        entity.setSnapshotType(SnapshotMetadata.SnapshotType.BASELINE);
        entity.setCreatedAt(Instant.now());
        entity.setStatus(SnapshotStatus.PENDING);

        snapshotRepo.save(entity);
        var found = snapshotRepo.findById(entity.getSnapshotId());

        assertThat(found).isPresent();
        assertThat(found.get().getCommitSha()).isEqualTo("abc123");
    }

    @Test
    void jobOptimisticLockingThrowsOnConcurrentUpdate() {
        JobEntity job = new JobEntity();
        job.setJobId(UUID.randomUUID());
        job.setJobType("SNAPSHOT");
        job.setStatus(JobStatusEnum.PENDING);
        job.setCreatedAt(Instant.now());
        jobRepo.saveAndFlush(job);

        JobEntity copy1 = jobRepo.findById(job.getJobId()).orElseThrow();
        JobEntity copy2 = jobRepo.findById(job.getJobId()).orElseThrow();

        copy1.setStatus(JobStatusEnum.RUNNING);
        jobRepo.saveAndFlush(copy1);

        copy2.setStatus(JobStatusEnum.FAILED);
        assertThrows(OptimisticLockingFailureException.class,
            () -> jobRepo.saveAndFlush(copy2));
    }

    @Test
    void parseArtifactUpsertBehavior() {
        UUID snapshotId = UUID.randomUUID();
        SnapshotEntity snapshot = new SnapshotEntity();
        snapshot.setSnapshotId(snapshotId);
        snapshot.setRepoUrl("https://github.com/org/repo");
        snapshot.setBranch("master");
        snapshot.setCommitSha("sha1");
        snapshot.setSnapshotType(SnapshotMetadata.SnapshotType.BASELINE);
        snapshot.setCreatedAt(Instant.now());
        snapshot.setStatus(SnapshotStatus.COMPLETED);
        snapshotRepo.save(snapshot);

        ParseArtifactEntity artifact = new ParseArtifactEntity();
        artifact.setSnapshotId(snapshotId);
        artifact.setArtifactType("JAVA_CLASS");
        artifact.setArtifactKey("com.example.Foo");
        artifact.setContentHash("hash-v1");
        artifact.setMinioPath("parsed-code/snap/Foo.json");
        artifact.setStatus(ParseArtifactStatus.PARSED);
        artifact.setCreatedAt(Instant.now());
        parseArtifactRepo.save(artifact);

        var existing = parseArtifactRepo.findBySnapshotIdAndArtifactTypeAndArtifactKey(
            snapshotId, "JAVA_CLASS", "com.example.Foo");
        assertThat(existing).isPresent();
        assertThat(existing.get().getContentHash()).isEqualTo("hash-v1");
    }
}
