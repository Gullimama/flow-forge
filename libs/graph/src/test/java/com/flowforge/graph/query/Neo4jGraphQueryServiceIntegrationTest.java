package com.flowforge.graph.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
class Neo4jGraphQueryServiceIntegrationTest {

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5.25-community")
        .withAdminPassword("testpass");

    Driver driver;
    Neo4jGraphQueryService queryService;

    @BeforeEach
    void setUp() {
        driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.basic("neo4j", "testpass"));
        queryService = new Neo4jGraphQueryService(driver);

        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (n) DETACH DELETE n");
                tx.run("""
                    CREATE (a:Service {name: 'api-gateway'})
                    CREATE (b:Service {name: 'order-service'})
                    CREATE (c:Service {name: 'payment-service'})
                    CREATE (a)-[:CALLS_HTTP]->(b)
                    CREATE (b)-[:CALLS_HTTP]->(c)
                    CREATE (b)-[:CONTAINS_CLASS]->(:Class {fqn: 'com.ex.OrderController'})
                        -[:HAS_METHOD]->(:Method {name: 'placeOrder', reactiveComplexity: 'BRANCHING'})
                        -[:EXPOSES_ENDPOINT]->(:Endpoint {httpMethod: 'POST', httpPath: '/orders'})
                    """);
                return null;
            });
        }
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
        }
    }

    @Test
    void getServiceNeighborhood_returnsNeighborsWithinHops() {
        var result = queryService.getServiceNeighborhood("order-service", 1);
        assertThat(result).isNotEmpty();
        var neighborNames = result.stream()
            .map(r -> {
                Object n = r.get("neighbor");
                if (n instanceof org.neo4j.driver.types.Node node) {
                    return node.get("name").asString();
                }
                return null;
            })
            .filter(java.util.Objects::nonNull)
            .toList();
        assertThat(neighborNames).contains("api-gateway", "payment-service");
    }

    @Test
    void getCallChain_findsShortestPath() {
        var result = queryService.getCallChain("api-gateway", "payment-service");
        assertThat(result).isNotEmpty();
    }

    @Test
    void getServiceEndpoints_returnsHttpEndpoints() {
        var result = queryService.getServiceEndpoints("order-service");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("method")).isEqualTo("POST");
        assertThat(result.get(0).get("path")).isEqualTo("/orders");
    }

    @Test
    void findComplexReactiveMethods_findsBranchingMethods() {
        var result = queryService.findComplexReactiveMethods();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("method")).isEqualTo("placeOrder");
    }
}
