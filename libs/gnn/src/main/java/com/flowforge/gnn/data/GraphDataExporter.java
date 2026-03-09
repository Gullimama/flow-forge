package com.flowforge.gnn.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.graph.query.Neo4jGraphQueryService;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Export Neo4j graph data to JSON for Python GNN training.
 */
@Component
public class GraphDataExporter {

    private final Neo4jGraphQueryService graphQuery;
    private final ObjectMapper objectMapper;

    public GraphDataExporter(Neo4jGraphQueryService graphQuery, ObjectMapper objectMapper) {
        this.graphQuery = graphQuery;
        this.objectMapper = objectMapper;
    }

    /**
     * Export graph data for a snapshot to a JSON file for training script consumption.
     */
    public void exportForTraining(UUID snapshotId, Path outputPath) throws Exception {
        var preparer = new GraphDataPreparer(graphQuery);
        var graphData = preparer.prepareGraphData(snapshotId);
        var export = Map.<String, Object>of(
            "node_features", graphData.nodeFeatures(),
            "edge_index", graphData.edgeIndex(),
            "node_labels", graphData.nodeLabels(),
            "num_nodes", graphData.numNodes(),
            "num_edges", graphData.numEdges()
        );
        objectMapper.writeValue(outputPath.toFile(), export);
    }
}
