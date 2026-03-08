package com.flowforge.ingest.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.common.entity.SnapshotStatus;
import com.flowforge.common.model.SnapshotMetadata.SnapshotType;
import com.flowforge.common.service.MetadataService;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.ingest.IngestTestApplication;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = IngestTestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@Tag("integration")
@Timeout(30)
class GitHubSnapshotWorkerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("flowforge_test")
        .withUsername("flowforge")
        .withPassword("flowforge");

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest")
        .withUserName("minioadmin")
        .withPassword("minioadmin");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("flowforge.minio.enabled", () -> "true");
        registry.add("flowforge.minio.endpoint", () -> minio.getS3URL());
        registry.add("flowforge.minio.access-key", () -> "minioadmin");
        registry.add("flowforge.minio.secret-key", () -> "minioadmin");
    }

    @Autowired private GitHubSnapshotWorker worker;
    @Autowired private MetadataService metadataService;
    @Autowired private MinioStorageClient storageClient;

    private Path localRepo;

    @BeforeEach
    void setUpRepo() throws Exception {
        storageClient.ensureBuckets();
        localRepo = Files.createTempDirectory("integration-repo");
        try (Git git = Git.init().setDirectory(localRepo.toFile()).call()) {
            Files.createDirectories(localRepo.resolve("services/booking/src/main/java"));
            Files.createDirectories(localRepo.resolve("services/booking/src/main/resources"));
            Files.writeString(
                localRepo.resolve("services/booking/src/main/java/Booking.java"),
                "public class Booking {}");
            Files.writeString(
                localRepo.resolve("services/booking/src/main/resources/application.yml"),
                "server:\n  port: 8080");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial commit").call();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (localRepo != null && Files.exists(localRepo)) {
            deleteRecursively(localRepo);
        }
    }

    @Test
    void baselineSnapshotEndToEnd() throws Exception {
        UUID jobId = metadataService.createJob("SNAPSHOT", Map.of());

        SnapshotResult result = worker.executeBaseline(
            jobId, localRepo.toUri().toString(), "master");

        assertThat(result.snapshotId()).isNotNull();
        assertThat(result.totalFiles()).isGreaterThanOrEqualTo(2);
        assertThat(result.javaFiles()).isGreaterThanOrEqualTo(1);
        assertThat(result.detectedServices()).contains("booking");

        var objects = storageClient.listObjects("raw-git", result.snapshotId().toString() + "/");
        assertThat(objects).isNotEmpty();

        var snapshot = metadataService.getSnapshot(result.snapshotId());
        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().getStatus()).isEqualTo(SnapshotStatus.COMPLETED);
    }

    @Test
    void refreshSnapshotOnlyUploadsChangedFiles() throws Exception {
        UUID baselineJobId = metadataService.createJob("SNAPSHOT", Map.of());
        worker.executeBaseline(baselineJobId, localRepo.toUri().toString(), "master");

        try (Git git = Git.open(localRepo.toFile())) {
            Files.writeString(
                localRepo.resolve("services/booking/src/main/java/NewService.java"),
                "public class NewService {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add NewService").call();
        }

        UUID refreshJobId = metadataService.createJob("SNAPSHOT_REFRESH", Map.of());
        SnapshotResult refreshResult = worker.executeRefresh(refreshJobId);

        assertThat(refreshResult.snapshotId()).isNotNull();
        assertThat(refreshResult.totalFiles()).isEqualTo(1);

        var snapshot = metadataService.getSnapshot(refreshResult.snapshotId());
        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().getSnapshotType()).isEqualTo(SnapshotType.REFRESH);
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (Files.exists(path)) {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws java.io.IOException {
                    Files.delete(f);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path d, java.io.IOException e) throws java.io.IOException {
                    if (e != null) throw e;
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
