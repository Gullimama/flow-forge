package com.flowforge.synthesis.model;

/**
 * Edge in the dependency graph.
 */
public record DependencyEdge(String from, String to, String label) {
}
