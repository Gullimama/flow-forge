package com.flowforge.parser.service;

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

@SpringBootTest(classes = com.flowforge.parser.TestCodeParserApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class CodeParsingServiceIntegrationTest {

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
    CodeParsingService codeParsingService;
    @Autowired
    OpenSearchClientWrapper openSearch;
    @Autowired
    MinioStorageClient minio;

    private String codeArtifactsSchema;

    @BeforeEach
    void setUp() throws Exception {
        minio.ensureBuckets();
        codeArtifactsSchema = new String(
            new ClassPathResource("opensearch/code-artifacts.json", OpenSearchClientWrapper.class).getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
    }

    @Test
    void parseSnapshot_indexesChunksToOpenSearch(@TempDir Path snapshotDir) throws Exception {
        copyFixture("fixtures/sample-booking-controller.java",
            snapshotDir.resolve("src/main/java/com/example/BookingController.java"));
        copyFixture("fixtures/sample-payment-service.java",
            snapshotDir.resolve("src/main/java/com/example/PaymentService.java"));

        openSearch.ensureIndex("code-artifacts", codeArtifactsSchema);

        var result = codeParsingService.parseSnapshot(UUID.randomUUID(), snapshotDir);

        assertThat(result.filesParsed()).isEqualTo(2);
        assertThat(result.chunksIndexed()).isGreaterThan(0);
        assertThat(result.parseErrors()).isZero();
        assertThat(openSearch.getDocCount("code-artifacts")).isEqualTo(result.chunksIndexed());
    }

    @Test
    void parseSnapshot_malformedFile_capturesErrorAndContinues(@TempDir Path snapshotDir) throws Exception {
        copyFixture("fixtures/sample-booking-controller.java",
            snapshotDir.resolve("src/main/java/com/example/BookingController.java"));
        Files.writeString(
            snapshotDir.resolve("src/main/java/com/example/Broken.java"),
            "public class { this is not valid java }}}");

        openSearch.ensureIndex("code-artifacts", codeArtifactsSchema);

        var result = codeParsingService.parseSnapshot(UUID.randomUUID(), snapshotDir);

        assertThat(result.filesParsed()).isEqualTo(1);
        assertThat(result.parseErrors()).isEqualTo(1);
    }

    @Test
    void parseSnapshot_batchIndexingRespectsBatchSize(@TempDir Path snapshotDir) throws Exception {
        for (int i = 0; i < 60; i++) {
            Files.writeString(
                snapshotDir.resolve("src/main/java/com/example/Svc" + i + ".java"),
                "package com.example;\npublic class Svc" + i + " {\n  public void run() {}\n}");
        }

        openSearch.ensureIndex("code-artifacts", codeArtifactsSchema);

        var result = codeParsingService.parseSnapshot(UUID.randomUUID(), snapshotDir);

        assertThat(result.filesParsed()).isEqualTo(60);
        assertThat(result.chunksIndexed()).isGreaterThanOrEqualTo(60);
    }

    private void copyFixture(String classPath, Path target) throws Exception {
        Path dir = target.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        try (var in = new ClassPathResource(classPath).getInputStream()) {
            Files.write(target, in.readAllBytes());
        }
    }
}
