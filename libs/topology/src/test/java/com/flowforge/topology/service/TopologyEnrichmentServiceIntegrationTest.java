package com.flowforge.topology.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.client.OpenSearchClientWrapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

@SpringBootTest(classes = com.flowforge.topology.TestTopologyApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class TopologyEnrichmentServiceIntegrationTest {

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
    TopologyEnrichmentService enrichmentService;
    @Autowired
    OpenSearchClientWrapper openSearch;
    @Autowired
    MinioStorageClient minio;

    private String configArtifactsSchema;

    @BeforeEach
    void setUp() throws Exception {
        minio.ensureBuckets();
        configArtifactsSchema = new String(
            new ClassPathResource("opensearch/config-artifacts.json", OpenSearchClientWrapper.class).getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
    }

    @Test
    void enrichSnapshot_fullTopology_indexesNodesAndEdges(@TempDir Path snapshotDir) throws Exception {
        openSearch.ensureIndex("config-artifacts", configArtifactsSchema);
        setupSnapshotFixtures(snapshotDir);

        var result = enrichmentService.enrichSnapshot(UUID.randomUUID(), snapshotDir);

        assertThat(result.nodeCount()).isGreaterThan(0);
        assertThat(result.edgeCount()).isGreaterThan(0);
        assertThat(openSearch.getDocCount("config-artifacts"))
            .isEqualTo(result.nodeCount() + result.edgeCount());
    }

    @Test
    void enrichSnapshot_storesTopologyGraphInMinio(@TempDir Path snapshotDir) throws Exception {
        setupSnapshotFixtures(snapshotDir);
        var snapshotId = UUID.randomUUID();

        enrichmentService.enrichSnapshot(snapshotId, snapshotDir);

        byte[] data = minio.getObject("evidence", "topology/" + snapshotId + ".json");
        String json = new String(data, StandardCharsets.UTF_8);
        assertThat(json).isNotBlank();
        assertThat(json).contains("nodes");
        assertThat(json).contains("edges");
    }

    @Test
    void enrichSnapshot_istioManifests_addServiceDependencyEdges(@TempDir Path snapshotDir) throws Exception {
        setupSnapshotFixtures(snapshotDir);
        copyFixture("sample-virtualservice.yaml", snapshotDir.resolve("istio").resolve("booking-vs.yaml"));

        var result = enrichmentService.enrichSnapshot(UUID.randomUUID(), snapshotDir);

        assertThat(result.edgeCount()).isGreaterThan(0);
    }

    private void setupSnapshotFixtures(Path snapshotDir) throws Exception {
        Path k8sDir = Files.createDirectories(snapshotDir.resolve("k8s"));
        copyFixture("sample-deployment.yaml", k8sDir.resolve("booking-deployment.yaml"));
        copyFixture("sample-ingress.yaml", k8sDir.resolve("ingress.yaml"));

        Path configDir = Files.createDirectories(snapshotDir.resolve("config").resolve("booking-service"));
        copyFixture("sample-application.yml", configDir.resolve("application.yml"));

        Files.createDirectories(snapshotDir.resolve("istio"));
    }

    private void copyFixture(String classPath, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        try (var in = new ClassPathResource(classPath).getInputStream()) {
            Files.write(target, in.readAllBytes());
        }
    }
}
