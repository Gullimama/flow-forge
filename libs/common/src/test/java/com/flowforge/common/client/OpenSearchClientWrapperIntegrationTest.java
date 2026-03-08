package com.flowforge.common.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.common.TestJpaApplication;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = TestJpaApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class OpenSearchClientWrapperIntegrationTest {

    @Container
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:2.18.0"))
        .withEnv("discovery.type", "single-node")
        .withEnv("plugins.security.disabled", "true")
        .withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", "admin")
        .withExposedPorts(9200);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String url = "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200);
        registry.add("flowforge.opensearch.hosts[0]", () -> url);
        registry.add("flowforge.opensearch.username", () -> "admin");
        registry.add("flowforge.opensearch.password", () -> "admin");
        registry.add("flowforge.opensearch.index-prefix", () -> "");
    }

    @Autowired
    OpenSearchClientWrapper wrapper;

    private static String loadResource(String path) {
        try {
            try (InputStream in = new ClassPathResource(path).getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void ensureIndex_createsIndexWithSettings() throws Exception {
        String schema = loadResource("opensearch/code-artifacts.json");
        wrapper.ensureIndex("code-artifacts-test", schema);

        assertThat(wrapper.healthCheck()).isTrue();
        assertThat(wrapper.getDocCount("code-artifacts-test")).isZero();
    }

    @Test
    void ensureIndex_calledTwice_isIdempotent() throws Exception {
        String schema = loadResource("opensearch/code-artifacts.json");
        wrapper.ensureIndex("idempotent-test", schema);
        assertThat(wrapper.getDocCount("idempotent-test")).isZero();
        wrapper.ensureIndex("idempotent-test", schema);
        assertThat(wrapper.getDocCount("idempotent-test")).isZero();
    }

    @Test
    void bulkIndex_indexesDocumentsAndReturnsNoErrors() throws Exception {
        wrapper.ensureIndex("bulk-test", loadResource("opensearch/code-artifacts.json"));

        var docs = List.of(
            Map.<String, Object>of("service_name", "booking-service", "content", "public class BookingController", "chunk_type", "CLASS_SIGNATURE"),
            Map.<String, Object>of("service_name", "payment-service", "content", "public void processPayment", "chunk_type", "METHOD"));

        OpenSearchClientWrapper.BulkResult response = wrapper.bulkIndex("bulk-test", docs);

        assertThat(response.errors()).isFalse();
        wrapper.refreshIndex("bulk-test");
        assertThat(wrapper.getDocCount("bulk-test")).isEqualTo(2);
    }

    @Test
    void multiMatchSearch_returnsScoredResults() throws Exception {
        wrapper.ensureIndex("search-test", loadResource("opensearch/code-artifacts.json"));
        wrapper.bulkIndex("search-test", List.of(
            Map.<String, Object>of("content", "public class BookingController handles reservations", "service_name", "booking-service"),
            Map.<String, Object>of("content", "public class PaymentGateway processes payments", "service_name", "payment-service")));
        wrapper.refreshIndex("search-test");

        var hits = wrapper.multiMatchSearch("search-test", "booking reservation", List.of("content"), 10);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getSourceAsMap().get("service_name")).isEqualTo("booking-service");
    }

    @Test
    void deleteByQuery_removesMatchingDocuments() throws Exception {
        wrapper.ensureIndex("delete-test", loadResource("opensearch/code-artifacts.json"));
        wrapper.bulkIndex("delete-test", List.of(
            Map.<String, Object>of("snapshot_id", "snap-1", "content", "class A"),
            Map.<String, Object>of("snapshot_id", "snap-2", "content", "class B")));
        wrapper.refreshIndex("delete-test");

        wrapper.deleteByQuery("delete-test", Map.of("term", Map.of("snapshot_id", "snap-1")));
        wrapper.refreshIndex("delete-test");

        assertThat(wrapper.getDocCount("delete-test")).isEqualTo(1);
    }

    @Test
    void fullLifecycle_createIndexBulkSearchDelete() throws Exception {
        String schema = loadResource("opensearch/code-artifacts.json");
        String indexName = "code-artifacts-lifecycle";
        wrapper.ensureIndex(indexName, schema);

        var snapshotId = UUID.randomUUID().toString();
        var docs = IntStream.range(0, 100)
            .mapToObj(i -> Map.<String, Object>of(
                "snapshot_id", snapshotId,
                "service_name", "booking-service",
                "content", "public void method" + i + "() { return; }",
                "chunk_type", "METHOD"))
            .toList();

        wrapper.bulkIndex(indexName, docs);
        wrapper.refreshIndex(indexName);

        assertThat(wrapper.getDocCount(indexName)).isGreaterThanOrEqualTo(100);

        var hits = wrapper.multiMatchSearch(indexName, "method50", List.of("content"), 5);
        assertThat(hits).isNotEmpty();

        wrapper.deleteByQuery(indexName, Map.of("term", Map.of("snapshot_id", snapshotId)));
        wrapper.refreshIndex(indexName);

        assertThat(wrapper.getDocCount(indexName)).isZero();
    }

    @Test
    void healthCheck_returnsTrue() {
        assertThat(wrapper.healthCheck()).isTrue();
    }
}
