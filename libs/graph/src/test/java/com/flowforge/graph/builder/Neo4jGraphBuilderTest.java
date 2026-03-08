package com.flowforge.graph.builder;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.parser.model.ParsedClass;
import com.flowforge.parser.model.ParsedField;
import com.flowforge.parser.model.ParsedMethod;
import com.flowforge.parser.model.ReactiveComplexity;
import com.flowforge.topology.model.ResourceLimits;
import com.flowforge.topology.model.TopologyNode;
import com.flowforge.topology.service.TopologyEnrichmentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class Neo4jGraphBuilderTest {

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5.25-community")
        .withAdminPassword("testpass");

    Driver driver;
    Neo4jGraphBuilder graphBuilder;

    @BeforeEach
    void setUp() {
        driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.basic("neo4j", "testpass"));
        graphBuilder = new Neo4jGraphBuilder(driver, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            try (var session = driver.session()) {
                session.executeWrite(tx -> {
                    tx.run("MATCH (n) DETACH DELETE n");
                    return null;
                });
            }
            driver.close();
        }
    }

    private static TopologyNode.ServiceNode serviceNode(String id, String name, String namespace, String image, int replicas) {
        return new TopologyNode.ServiceNode(id, name, namespace, image, replicas,
            Map.of(), List.of(), new ResourceLimits("", "", "", ""), List.of());
    }

    @Test
    void buildGraph_createsServiceNodeForEachTopologyNode() {
        UUID snapshotId = UUID.randomUUID();
        var topology = new TopologyEnrichmentService.TopologyGraph(snapshotId, List.of(
            serviceNode("svc:order-service", "order-service", "default", "img:1.0", 2),
            serviceNode("svc:payment-service", "payment-service", "default", "img:2.0", 1)
        ), List.of());

        var result = graphBuilder.buildGraph(snapshotId, topology, List.of(), Map.of());

        assertThat(result.nodesCreated()).isEqualTo(2);
    }

    @Test
    void buildGraph_createsEndpointNodesOnlyForHttpMethods() {
        ParsedMethod methodWithHttp = new ParsedMethod("getBooking", "Mono<Booking>",
            List.of(), List.of(), List.of(), true, ReactiveComplexity.LINEAR,
            List.of("GET"), Optional.of("/bookings/{id}"), 10, 20, "");
        ParsedMethod methodNoHttp = new ParsedMethod("processInternal", "void",
            List.of(), List.of(), List.of(), false, ReactiveComplexity.NONE,
            List.of(), Optional.empty(), 20, 30, "");
        ParsedClass clazz = new ParsedClass("com.example.BookingController", "BookingController", "com.example",
            "BookingController.java", ParsedClass.ClassType.CLASS, List.of(), List.of(), Optional.empty(),
            List.of(methodWithHttp, methodNoHttp), List.of(), List.of(), 1, 100, "");

        var topology = new TopologyEnrichmentService.TopologyGraph(UUID.randomUUID(), List.of(
            serviceNode("svc:booking", "booking-service", "default", "img", 1)
        ), List.of());
        var graphResult = graphBuilder.buildGraph(UUID.randomUUID(), topology, List.of(clazz), Map.of());

        // 1 Service + 1 Class + 2 Methods + 1 Endpoint (only the HTTP method)
        assertThat(graphResult.nodesCreated()).isEqualTo(5);
    }

    @Test
    void buildGraph_createsInjectionEdgesForInjectedFields() {
        ParsedField field = new ParsedField("bookingRepo", "com.example.BookingRepository", List.of(), true);
        ParsedClass depClass = new ParsedClass("com.example.BookingRepository", "BookingRepository", "com.example",
            "BookingRepository.java", ParsedClass.ClassType.INTERFACE, List.of(), List.of(), Optional.empty(),
            List.of(), List.of(), List.of(), 1, 10, "");
        ParsedClass clazz = new ParsedClass("com.example.BookingService", "BookingService", "com.example",
            "BookingService.java", ParsedClass.ClassType.CLASS, List.of(), List.of(), Optional.empty(),
            List.of(), List.of(field), List.of(), 1, 50, "");

        var topology = new TopologyEnrichmentService.TopologyGraph(UUID.randomUUID(), List.of(
            serviceNode("svc:booking", "booking-service", "default", "img", 1)
        ), List.of());
        graphBuilder.buildGraph(UUID.randomUUID(), topology, List.of(depClass, clazz), Map.of());

        try (var session = driver.session()) {
            var count = session.run("MATCH ()-[r:INJECTS]->() RETURN count(r) AS cnt").single().get("cnt").asInt();
            assertThat(count).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void createTopologyNode_handlesAllSwitchCases() {
        UUID snapshotId = UUID.randomUUID();
        var nodes = List.<TopologyNode>of(
            serviceNode("svc:svc", "svc", "ns", "img", 1),
            new TopologyNode.KafkaTopicNode("kafka:t1", "topic-1", 3, 1),
            new TopologyNode.DatabaseNode("db:db1", "db-1", "POSTGRES", "db-host", 5432),
            new TopologyNode.IngressNode("ingress:ingress-1", "ingress-1", List.of()),
            new TopologyNode.ConfigMapNode("cm:config-1", "config-1", Map.of())
        );
        var topology = new TopologyEnrichmentService.TopologyGraph(snapshotId, nodes, List.of());

        var result = graphBuilder.buildGraph(snapshotId, topology, List.of(), Map.of());

        assertThat(result.nodesCreated()).isEqualTo(5);
    }
}
