package com.flowforge.logparser.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.client.OpenSearchClientWrapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = com.flowforge.logparser.TestLogParserApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class LogParsingServiceIntegrationTest {

    @Container
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:2.18.0"))
        .withEnv("discovery.type", "single-node")
        .withEnv("plugins.security.disabled", "true")
        .withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", "admin")
        .withExposedPorts(9200);

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest")
        .withUserName("minioadmin")
        .withPassword("minioadmin");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String openSearchUrl = "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200);
        registry.add("flowforge.opensearch.hosts[0]", () -> openSearchUrl);
        registry.add("flowforge.opensearch.username", () -> "admin");
        registry.add("flowforge.opensearch.password", () -> "admin");
        registry.add("flowforge.opensearch.index-prefix", () -> "");
        registry.add("flowforge.minio.enabled", () -> "true");
        registry.add("flowforge.minio.endpoint", MINIO::getS3URL);
        registry.add("flowforge.minio.access-key", () -> "minioadmin");
        registry.add("flowforge.minio.secret-key", () -> "minioadmin");
    }

    @Autowired
    LogParsingService logParsingService;
    @Autowired
    OpenSearchClientWrapper openSearch;
    @Autowired
    MinioStorageClient minio;

    private String runtimeEventsSchema;

    @BeforeEach
    void setUp() throws Exception {
        minio.ensureBuckets();
        runtimeEventsSchema = new String(
            new ClassPathResource("opensearch/runtime-events.json", OpenSearchClientWrapper.class).getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
    }

    @Test
    void parseSnapshotLogs_indexesEventsToOpenSearch(@TempDir Path logDir) throws Exception {
        copyFixture("sample-micronaut.log", logDir.resolve("booking-service").resolve("app.log"));
        copyFixture("sample-istio-access.log", logDir.resolve("booking-service").resolve("istio-proxy.log"));

        openSearch.ensureIndex("runtime-events", runtimeEventsSchema);

        var result = logParsingService.parseSnapshotLogs(UUID.randomUUID(), logDir);

        assertThat(result.eventsIndexed()).isGreaterThan(0);
        assertThat(result.uniqueTemplates()).isGreaterThan(0);
        assertThat(openSearch.getDocCount("runtime-events")).isEqualTo(result.eventsIndexed());
    }

    @Test
    void parseSnapshotLogs_storesDrainClustersInMinio(@TempDir Path logDir) throws Exception {
        copyFixture("sample-micronaut.log", logDir.resolve("booking-service").resolve("app.log"));
        var snapshotId = UUID.randomUUID();

        logParsingService.parseSnapshotLogs(snapshotId, logDir);

        List<Map<String, Object>> clusters = minio.getJson("evidence",
            "drain-clusters/" + snapshotId + ".json",
            new TypeReference<List<Map<String, Object>>>() {});
        assertThat(clusters).isNotEmpty();
    }

    @Test
    void parseSnapshotLogs_serviceNameInferredFromDirectory(@TempDir Path logDir) throws Exception {
        Files.createDirectories(logDir.resolve("payment-service"));
        Files.writeString(logDir.resolve("payment-service").resolve("app.log"),
            "2024-01-15T10:30:45.123 INFO [main] com.example.Svc - Started\n");

        openSearch.ensureIndex("runtime-events", runtimeEventsSchema);

        var snapshotId = UUID.randomUUID();
        logParsingService.parseSnapshotLogs(snapshotId, logDir);

        var hits = openSearch.multiMatchSearch("runtime-events", "Started", List.of("message"), 10);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getSourceAsMap().get("service_name")).isEqualTo("payment-service");
    }

    private void copyFixture(String classPath, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        try (var in = new ClassPathResource(classPath).getInputStream()) {
            Files.write(target, in.readAllBytes());
        }
    }
}
