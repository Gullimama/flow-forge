package com.flowforge.gnn;

import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import com.flowforge.gnn.data.GraphData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Test fixtures for GNN module.
 */
public final class TestFixtures {

    private static final int NODE_FEATURE_DIM = 32;

    private TestFixtures() {}

    public static List<Map<String, Object>> sampleGraphNodes(int count) {
        var nodes = new ArrayList<Map<String, Object>>();
        var types = new String[] { "Service", "Class", "Method", "Endpoint" };
        for (int i = 0; i < count; i++) {
            nodes.add(Map.of(
                "id", "n" + i,
                "type", types[i % types.length],
                "degree", (long) ThreadLocalRandom.current().nextInt(0, 10),
                "isReactive", i % 3 == 0,
                "reactiveComplexity", i % 2 == 0 ? "NONE" : "LINEAR"
            ));
        }
        return nodes;
    }

    public static List<Map<String, Object>> sampleGraphEdges(int count, int maxNodeIndex) {
        var edges = new ArrayList<Map<String, Object>>();
        var r = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            int s = r.nextInt(0, maxNodeIndex + 1);
            int t = r.nextInt(0, maxNodeIndex + 1);
            if (s == t) t = (t + 1) % (maxNodeIndex + 1);
            edges.add(Map.of("source", "n" + s, "target", "n" + t));
        }
        return edges;
    }

    /** Edges with source/target as string ids for buildEdgeIndex (nodes have id "0", "1", "2"). */
    public static List<Map<String, Object>> edgesForIndices(List<int[]> sourceTargetPairs) {
        var edges = new ArrayList<Map<String, Object>>();
        for (var pair : sourceTargetPairs) {
            edges.add(Map.of("source", String.valueOf(pair[0]), "target", String.valueOf(pair[1])));
        }
        return edges;
    }

    public static List<Map<String, Object>> nodesWithIds(String... ids) {
        var nodes = new ArrayList<Map<String, Object>>();
        for (String id : ids) {
            nodes.add(Map.of("id", id, "type", "Service", "degree", 0L, "isReactive", false, "reactiveComplexity", "NONE"));
        }
        return nodes;
    }

    public static GraphData sampleGraphData(int numNodes, int numEdges) {
        var nodeFeatures = new float[numNodes][NODE_FEATURE_DIM];
        for (int i = 0; i < numNodes; i++) {
            nodeFeatures[i][0] = 1.0f;
        }
        var edgeIndex = new long[2][numEdges];
        for (int e = 0; e < numEdges; e++) {
            edgeIndex[0][e] = e % numNodes;
            edgeIndex[1][e] = (e + 1) % numNodes;
        }
        var edgeFeatures = new float[numEdges][4];
        for (int e = 0; e < numEdges; e++) {
            edgeFeatures[e][0] = 1.0f;
        }
        var nodeLabels = new int[numNodes];
        return new GraphData(nodeFeatures, edgeIndex, edgeFeatures, nodeLabels, numNodes, numEdges);
    }

    public static GraphData smallTestGraphData() {
        return sampleGraphData(5, 7);
    }

    public static NDList linkPredictionOutput(int numNodes, float[] scores) {
        try (var manager = NDManager.newBaseManager()) {
            var arr = manager.create(scores);
            return new NDList(arr);
        }
    }

    public static NDList nodeClassificationOutput(int numNodes, int numClasses) {
        try (var manager = NDManager.newBaseManager()) {
            var probs = new float[numNodes * numClasses];
            var r = ThreadLocalRandom.current();
            for (int i = 0; i < numNodes; i++) {
                float sum = 0;
                for (int c = 0; c < numClasses; c++) {
                    probs[i * numClasses + c] = r.nextFloat();
                    sum += probs[i * numClasses + c];
                }
                for (int c = 0; c < numClasses; c++) {
                    probs[i * numClasses + c] /= sum;
                }
            }
            var arr = manager.create(probs).reshape(numNodes, numClasses);
            return new NDList(arr);
        }
    }

    public static NDList nodeClassificationOutputFixed(float[][] probs) {
        try (var manager = NDManager.newBaseManager()) {
            int rows = probs.length;
            int cols = probs[0].length;
            var flat = new float[rows * cols];
            for (int i = 0; i < rows; i++) {
                System.arraycopy(probs[i], 0, flat, i * cols, cols);
            }
            var arr = manager.create(flat).reshape(rows, cols);
            return new NDList(arr);
        }
    }
}
