package com.flowforge.gnn.data;

import com.flowforge.graph.query.Neo4jGraphQueryService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Extracts graph structure from Neo4j into tensor format for GNN.
 */
@Component
public class GraphDataPreparer {

    private static final int NODE_FEATURE_DIM = 32;
    private static final int EDGE_FEATURE_DIM = 4;
    private static final String[] NODE_TYPES = {
        "Service", "Class", "Method", "Endpoint", "KafkaTopic",
        "Database", "Ingress", "ConfigMap", "LogTemplate", "AnomalyEpisode"
    };

    private final Neo4jGraphQueryService graphQuery;

    public GraphDataPreparer(Neo4jGraphQueryService graphQuery) {
        this.graphQuery = graphQuery;
    }

    /**
     * Extract graph structure from Neo4j into tensor format for GNN.
     */
    public GraphData prepareGraphData(UUID snapshotId) {
        var nodes = graphQuery.getAllNodes(snapshotId);
        var edges = graphQuery.getAllEdges(snapshotId);

        var nodeFeatures = buildNodeFeatureMatrix(nodes);
        var nodeLabels = buildNodeLabelVector(nodes);
        var edgeIndex = buildEdgeIndex(nodes, edges);
        int numEdges = edgeIndex[0].length;
        var edgeFeatures = buildEdgeFeatureMatrix(numEdges);

        return new GraphData(
            nodeFeatures,
            edgeIndex,
            edgeFeatures,
            nodeLabels,
            nodes.size(),
            numEdges
        );
    }

    /**
     * One-hot index for node type (0..9). Package visibility for tests.
     */
    int nodeTypeIndex(String type) {
        if (type == null || type.isBlank()) {
            return 0;
        }
        for (int i = 0; i < NODE_TYPES.length; i++) {
            if (NODE_TYPES[i].equalsIgnoreCase(type)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Encode node features: one-hot type (0-9), degree (10), isReactive (11), complexity ordinal (12).
     * Package visibility for tests.
     */
    float[] encodeNodeFeatures(Map<String, Object> node, int dim) {
        var features = new float[dim];
        int typeIdx = nodeTypeIndex((String) node.get("type"));
        features[typeIdx] = 1.0f;

        int idx = 10;
        if (idx < dim) {
            Object degree = node.getOrDefault("degree", 0L);
            features[idx++] = degree instanceof Number n ? n.floatValue() : 0f;
        }
        if (idx < dim) {
            Object reactive = node.getOrDefault("isReactive", false);
            features[idx++] = Boolean.TRUE.equals(reactive) || "true".equalsIgnoreCase(String.valueOf(reactive)) ? 1.0f : 0.0f;
        }
        if (idx < dim) {
            features[idx] = complexityOrdinal((String) node.getOrDefault("reactiveComplexity", "NONE"));
        }
        return features;
    }

    private float complexityOrdinal(String complexity) {
        if (complexity == null) return 0f;
        return switch (complexity.toUpperCase()) {
            case "NONE" -> 0f;
            case "LINEAR" -> 1f;
            case "BRANCHING" -> 2f;
            case "COMPLEX" -> 3f;
            default -> 0f;
        };
    }

    private float[][] buildNodeFeatureMatrix(List<Map<String, Object>> nodes) {
        var features = new float[nodes.size()][NODE_FEATURE_DIM];
        for (int i = 0; i < nodes.size(); i++) {
            features[i] = encodeNodeFeatures(nodes.get(i), NODE_FEATURE_DIM);
        }
        return features;
    }

    private int[] buildNodeLabelVector(List<Map<String, Object>> nodes) {
        var labels = new int[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            String type = (String) nodes.get(i).get("type");
            labels[i] = nodeTypeIndex(type);
        }
        return labels;
    }

    /**
     * Build edge index [2][numEdges] with source and target node indices.
     * Nodes are ordered as returned by getAllNodes; edges use elementId -> index map.
     * Package visibility for tests.
     */
    long[][] buildEdgeIndex(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        var idToIndex = new HashMap<String, Integer>();
        for (int i = 0; i < nodes.size(); i++) {
            Object id = nodes.get(i).get("id");
            if (id != null) {
                idToIndex.put(id.toString(), i);
            }
        }

        var validEdges = new ArrayList<int[]>();
        for (var edge : edges) {
            Object src = edge.get("source");
            Object tgt = edge.get("target");
            if (src != null && tgt != null) {
                Integer si = idToIndex.get(src.toString());
                Integer ti = idToIndex.get(tgt.toString());
                if (si != null && ti != null) {
                    validEdges.add(new int[] { si, ti });
                }
            }
        }

        var edgeIndex = new long[2][validEdges.size()];
        for (int e = 0; e < validEdges.size(); e++) {
            edgeIndex[0][e] = validEdges.get(e)[0];
            edgeIndex[1][e] = validEdges.get(e)[1];
        }
        return edgeIndex;
    }

    private float[][] buildEdgeFeatureMatrix(int numEdges) {
        var features = new float[numEdges][EDGE_FEATURE_DIM];
        for (int e = 0; e < numEdges; e++) {
            features[e][0] = 1.0f;
        }
        return features;
    }
}
