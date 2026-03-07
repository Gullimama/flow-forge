package com.flowforge.common.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.flowforge.common.model.SnapshotMetadata;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Tag("integration")
class MinioStorageClientIntegrationTest {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest")
        .withUserName("minioadmin")
        .withPassword("minioadmin")
        .withReuse(true);

    private MinioStorageClient storageClient;
    private MinioClient rawClient;
    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        rawClient = MinioClient.builder()
            .endpoint(minio.getS3URL())
            .credentials("minioadmin", "minioadmin")
            .build();
        storageClient = new MinioStorageClient(rawClient, objectMapper);
        storageClient.ensureBuckets();
    }

    @Test
    void ensureBucketsCreatesAllEightBuckets() throws Exception {
        List<String> expected = List.of(
            "raw-git", "raw-logs", "parsed-code", "parsed-logs",
            "graph-artifacts", "research-output", "model-artifacts", "evidence"
        );
        for (String bucket : expected) {
            assertThat(rawClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucket).build()
            )).isTrue();
        }
    }

    @Test
    void putAndGetObjectRoundTrip() {
        byte[] data = "hello flowforge".getBytes(StandardCharsets.UTF_8);

        storageClient.putObject("raw-git", "test/hello.txt", data, "text/plain");
        byte[] retrieved = storageClient.getObject("raw-git", "test/hello.txt");

        assertThat(retrieved).isEqualTo(data);
    }

    @Test
    void putAndGetJsonRoundTrip() {
        var meta = new SnapshotMetadata(
            UUID.randomUUID(), "https://github.com/org/repo", "master",
            "sha1", SnapshotMetadata.SnapshotType.BASELINE,
            Instant.now(), List.of("a.java", "b.yaml")
        );

        storageClient.putJson("parsed-code", "snap/meta.json", meta);
        var retrieved = storageClient.getJson("parsed-code", "snap/meta.json",
            SnapshotMetadata.class);

        assertThat(retrieved.repoUrl()).isEqualTo(meta.repoUrl());
        assertThat(retrieved.changedFiles()).containsExactly("a.java", "b.yaml");
    }

    @Test
    void listObjectsReturnsCorrectCount() {
        for (int i = 0; i < 5; i++) {
            storageClient.putObject("evidence", "report/" + i + ".json",
                "{}".getBytes(), "application/json");
        }

        List<MinioObjectInfo> objects = storageClient.listObjects("evidence", "report/");

        assertThat(objects).hasSize(5);
    }

    @Test
    void streamDownloadReturnsFullContent() throws Exception {
        byte[] largePayload = new byte[1024 * 1024]; // 1 MB
        new Random(42).nextBytes(largePayload);
        storageClient.putObject("raw-logs", "large.bin", largePayload,
            "application/octet-stream");

        try (InputStream stream = storageClient.getObjectStream("raw-logs", "large.bin")) {
            byte[] downloaded = stream.readAllBytes();
            assertThat(downloaded).isEqualTo(largePayload);
        }
    }

    @Test
    void deleteObjectRemovesIt() {
        storageClient.putObject("evidence", "temp.txt", "x".getBytes(), "text/plain");
        assertThat(storageClient.objectExists("evidence", "temp.txt")).isTrue();

        storageClient.deleteObject("evidence", "temp.txt");

        assertThat(storageClient.objectExists("evidence", "temp.txt")).isFalse();
    }

    @Test
    void copyObjectBetweenBuckets() {
        storageClient.putObject("raw-git", "src.txt", "data".getBytes(), "text/plain");

        storageClient.copyObject("raw-git", "src.txt", "evidence", "dst.txt");

        byte[] copied = storageClient.getObject("evidence", "dst.txt");
        assertThat(new String(copied)).isEqualTo("data");
    }

    @Test
    void healthCheckReturnsTrueWithRunningMinio() {
        assertThat(storageClient.healthCheck()).isTrue();
    }
}
