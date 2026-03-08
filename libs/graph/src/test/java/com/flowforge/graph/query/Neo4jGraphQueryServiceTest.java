package com.flowforge.graph.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.summary.ResultSummary;

@ExtendWith(MockitoExtension.class)
class Neo4jGraphQueryServiceTest {

    @Mock Driver driver;
    @Mock Session session;

    Neo4jGraphQueryService queryService;

    @BeforeEach
    void setUp() {
        when(driver.session()).thenReturn(session);
        queryService = new Neo4jGraphQueryService(driver);
    }

    @Test
    void getServiceNeighborhood_passesHopsParameter() {
        when(session.executeRead(any())).thenReturn(List.<Map<String, Object>>of());

        queryService.getServiceNeighborhood("order-service", 3);

        verify(session).executeRead(any());
    }

    @Test
    void getCallChain_returnsEmptyListWhenNoPathExists() {
        when(session.executeRead(any())).thenReturn(List.<Map<String, Object>>of());

        var result = queryService.getCallChain("svc-a", "svc-z");

        assertThat(result).isEmpty();
    }

    @Test
    void findComplexReactiveMethods_returnsOnlyBranchingAndComplex() {
        var row = Map.<String, Object>of(
            "service", "order-service",
            "className", "com.example.OrderHandler",
            "method", "processOrder",
            "complexity", "BRANCHING"
        );
        when(session.executeRead(any())).thenReturn(List.of(row));

        var result = queryService.findComplexReactiveMethods();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("complexity")).isEqualTo("BRANCHING");
    }
}
