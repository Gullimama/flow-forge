package com.flowforge.vectorstore.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.flowforge.common.config.FlowForgeProperties;
import com.flowforge.vectorstore.config.QdrantConfig;

import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.List;
import java.util.Optional;

@Testcontainers
@SpringBootTest(classes = VectorStoreServiceIntegrationTest.TestConfig.class)
@Tag("integration")
class VectorStoreServiceIntegrationTest {

    @Container
    static GenericContainer<?> qdrant = new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:1.12.4"))
        .withExposedPorts(6333, 6334)
        .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/collections").forPort(6333).forStatusCode(200));

    @DynamicPropertySource
    static void qdrantProperties(DynamicPropertyRegistry registry) {
        registry.add("flowforge.qdrant.host", qdrant::getHost);
        registry.add("flowforge.qdrant.port", () -> qdrant.getMappedPort(6334));
    }

    @Autowired VectorStoreService vectorStoreService;

    @org.springframework.context.annotation.Configuration
    @EnableConfigurationProperties(FlowForgeProperties.class)
    @org.springframework.context.annotation.ComponentScan(basePackages = "com.flowforge.vectorstore")
    @Import(QdrantConfig.class)
    static class TestConfig {}

    @Test
    void addAndSearchCodeDocuments_roundTrip() {
        var docs = List.of(
            new Document("public Mono<Booking> getBooking(String id) { return repo.findById(id); }",
                java.util.Map.of("snapshot_id", "snap-1", "service_name", "booking-service", "class_fqn", "com.ex.BookingController")),
            new Document("public void processPayment(PaymentRequest req) { gateway.charge(req); }",
                java.util.Map.of("snapshot_id", "snap-1", "service_name", "payment-service", "class_fqn", "com.ex.PaymentService"))
        );
        vectorStoreService.addCodeDocuments(docs);
        var results = vectorStoreService.searchCode("booking endpoint handler", 5, "snap-1");
        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().getMetadata().get("service_name")).isEqualTo("booking-service");
    }

    @Test
    void searchLogs_filteredByServiceName() {
        var docs = List.of(
            new Document("ERROR connection timeout to payment-gateway",
                java.util.Map.of("snapshot_id", "snap-1", "service_name", "order-service")),
            new Document("ERROR connection timeout to database",
                java.util.Map.of("snapshot_id", "snap-1", "service_name", "payment-service"))
        );
        vectorStoreService.addLogDocuments(docs);
        var results = vectorStoreService.searchLogs("connection timeout", 5, "snap-1", Optional.of("order-service"));
        assertThat(results).allSatisfy(doc ->
            assertThat(doc.getMetadata().get("service_name")).isEqualTo("order-service"));
    }

    @Test
    void deleteBySnapshot_removesAllDocumentsForSnapshot() {
        var docs = List.of(
            new Document("content-1", java.util.Map.of("snapshot_id", "snap-to-delete", "service_name", "svc")),
            new Document("content-2", java.util.Map.of("snapshot_id", "snap-to-delete", "service_name", "svc"))
        );
        vectorStoreService.addCodeDocuments(docs);
        vectorStoreService.deleteBySnapshot("snap-to-delete");
        var results = vectorStoreService.searchCode("content", 10, "snap-to-delete");
        assertThat(results).isEmpty();
    }

    @Test
    void searchCode_belowSimilarityThreshold_returnsNoResults() {
        var docs = List.of(
            new Document("Java Spring Boot REST controller implementation",
                java.util.Map.of("snapshot_id", "snap-1", "service_name", "svc"))
        );
        vectorStoreService.addCodeDocuments(docs);
        var results = vectorStoreService.searchCode("completely unrelated query about cooking recipes", 5, "snap-1");
        assertThat(results).isEmpty();
    }
}
