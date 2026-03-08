package com.flowforge.graph.builder;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.parser.model.ParsedClass;
import com.flowforge.parser.model.ParsedMethod;
import com.flowforge.parser.model.ReactiveComplexity;
import com.flowforge.topology.model.TopologyEdge;
import com.flowforge.topology.model.TopologyNode;
import com.flowforge.topology.model.ResourceLimits;
import com.flowforge.topology.service.TopologyEnrichmentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Tag("integration")
class Neo4jGraphBuilderIntegrationTest {

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5.25-community")
        .withAdminPassword("testpass")
        .withPlugins("apoc");

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

    private static TopologyNode.ServiceNode serviceNode(String id, String name, String ns, String image, int replicas) {
        return new TopologyNode.ServiceNode(id, name, ns, image, replicas,
            Map.of(), List.of(), new ResourceLimits("", "", "", ""), List.of());
    }

    @Test
    void buildGraph_createsConstraintsInNeo4j() {
        UUID snapshotId = UUID.randomUUID();
        graphBuilder.buildGraph(snapshotId,
            new TopologyEnrichmentService.TopologyGraph(snapshotId,
                List.of(serviceNode("svc:svc", "svc", "ns", "img", 1)),
                List.of()),
            List.of(), Map.of());

        try (var session = driver.session()) {
            var constraints = session.run("SHOW CONSTRAINTS").list();
            assertThat(constraints).isNotEmpty();
        }
    }

    @Test
    void buildGraph_serviceNodesQueryableAfterBuild() {
        UUID snapshotId = UUID.randomUUID();
        var topology = new TopologyEnrichmentService.TopologyGraph(snapshotId, List.of(
            serviceNode("svc:order-service", "order-service", "default", "img:1.0", 2),
            serviceNode("svc:payment-service", "payment-service", "default", "img:2.0", 1)
        ), List.of(new TopologyEdge("svc:order-service", "svc:payment-service", TopologyEdge.EdgeType.HTTP_CALL,
            Map.of("url", "/pay"))));

        graphBuilder.buildGraph(snapshotId, topology, List.of(), Map.of());

        try (var session = driver.session()) {
            var count = session.run("MATCH (s:Service) RETURN count(s) AS cnt").single().get("cnt").asInt();
            assertThat(count).isEqualTo(2);

            var edges = session.run("MATCH ()-[r:CALLS_HTTP]->() RETURN count(r) AS cnt").single().get("cnt").asInt();
            assertThat(edges).isEqualTo(1);
        }
    }

    @Test
    void buildGraph_fullGraphWithClassesMethodsAndEndpoints() {
        UUID snapshotId = UUID.randomUUID();
        ParsedMethod method = new ParsedMethod("getBooking", "Mono<Booking>",
            List.of(), List.of(), List.of(), true, ReactiveComplexity.LINEAR,
            List.of("GET"), Optional.of("/bookings/{id}"), 10, 20, "");
        ParsedClass clazz = new ParsedClass("com.example.BookingController", "BookingController", "com.example",
            "BookingController.java", ParsedClass.ClassType.CLASS, List.of(), List.of(), Optional.empty(),
            List.of(method), List.of(), List.of(), 1, 100, "");
        var topology = new TopologyEnrichmentService.TopologyGraph(snapshotId,
            List.of(serviceNode("svc:booking-service", "booking-service", "default", "img", 1)),
            List.of());

        graphBuilder.buildGraph(snapshotId, topology, List.of(clazz), Map.of());

        try (var session = driver.session()) {
            var endpoints = session.run("MATCH (e:Endpoint) RETURN e.httpMethod AS m, e.httpPath AS p").list();
            assertThat(endpoints).hasSize(1);
            assertThat(endpoints.get(0).get("m").asString()).isEqualTo("GET");
        }
    }
}
