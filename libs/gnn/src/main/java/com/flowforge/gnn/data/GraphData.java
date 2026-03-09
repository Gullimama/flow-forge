package com.flowforge.gnn.data;

/**
 * Graph structure in tensor-friendly format for GNN inference.
 */
public record GraphData(
    float[][] nodeFeatures,
    long[][] edgeIndex,
    float[][] edgeFeatures,
    int[] nodeLabels,
    int numNodes,
    int numEdges
) {
}
