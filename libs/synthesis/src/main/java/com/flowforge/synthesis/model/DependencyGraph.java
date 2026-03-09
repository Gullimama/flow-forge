package com.flowforge.synthesis.model;

import java.util.List;

/**
 * Graph of dependencies (nodes and edges).
 */
public record DependencyGraph(
    List<String> nodes,
    List<DependencyEdge> edges
) {
}
