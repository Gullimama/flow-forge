package com.flowforge.gnn.data;

import com.flowforge.gnn.TestFixtures;
import com.flowforge.graph.query.Neo4jGraphQueryService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphDataPreparerTest {

    @Mock Neo4jGraphQueryService graphQuery;

    @InjectMocks GraphDataPreparer preparer;

    @Test
    void prepareGraphData_returnsCorrectDimensions() {
        var nodes = TestFixtures.sampleGraphNodes(20);
        var edges = TestFixtures.sampleGraphEdges(30, 19);
        when(graphQuery.getAllNodes(any(UUID.class))).thenReturn(nodes);
        when(graphQuery.getAllEdges(any(UUID.class))).thenReturn(edges);

        var data = preparer.prepareGraphData(UUID.randomUUID());

        assertThat(data.numNodes()).isEqualTo(20);
        assertThat(data.numEdges()).isEqualTo(30);
        assertThat(data.nodeFeatures().length).isEqualTo(20);
        assertThat(data.nodeFeatures()[0].length).isEqualTo(32);
        assertThat(data.edgeIndex().length).isEqualTo(2);
        assertThat(data.edgeIndex()[0].length).isEqualTo(30);
    }

    @Test
    void encodeNodeFeatures_setsOneHotForServiceType() {
        var serviceNode = Map.<String, Object>of(
            "type", "Service", "degree", 5, "isReactive", "true",
            "reactiveComplexity", "COMPLEX");

        var features = preparer.encodeNodeFeatures(serviceNode, 32);

        assertThat(features[preparer.nodeTypeIndex("Service")]).isEqualTo(1.0f);
        for (int i = 0; i < 10; i++) {
            if (i != preparer.nodeTypeIndex("Service")) {
                assertThat(features[i]).isEqualTo(0.0f);
            }
        }
    }

    @Test
    void encodeNodeFeatures_encodesReactiveFlag() {
        var reactiveNode = Map.<String, Object>of(
            "type", "Class", "degree", 3, "isReactive", "true",
            "reactiveComplexity", "BRANCHING");

        var features = preparer.encodeNodeFeatures(reactiveNode, 32);

        assertThat(features[11]).isEqualTo(1.0f);
    }

    @Test
    void encodeNodeFeatures_handlesDefaultValues() {
        var minimalNode = Map.<String, Object>of("type", "Method");

        var features = preparer.encodeNodeFeatures(minimalNode, 32);

        assertThat(features[10]).isEqualTo(0.0f);
        assertThat(features[11]).isEqualTo(0.0f);
    }

    @Test
    void buildEdgeIndex_createsSourceTargetPairs() {
        var nodes = TestFixtures.nodesWithIds("0", "1", "2");
        var edges = List.of(
            Map.<String, Object>of("source", "0", "target", "1"),
            Map.<String, Object>of("source", "1", "target", "2"),
            Map.<String, Object>of("source", "0", "target", "2"));

        var edgeIndex = preparer.buildEdgeIndex(nodes, edges);

        assertThat(edgeIndex[0]).containsExactly(0L, 1L, 0L);
        assertThat(edgeIndex[1]).containsExactly(1L, 2L, 2L);
    }

    @Test
    void prepareGraphData_emptyGraph_returnsEmptyData() {
        when(graphQuery.getAllNodes(any(UUID.class))).thenReturn(List.of());
        when(graphQuery.getAllEdges(any(UUID.class))).thenReturn(List.of());

        var data = preparer.prepareGraphData(UUID.randomUUID());

        assertThat(data.numNodes()).isZero();
        assertThat(data.numEdges()).isZero();
    }
}
