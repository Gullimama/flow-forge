package com.flowforge.synthesis.model;

/**
 * Version conflict for an artifact across services.
 */
public record DependencyConflict(
    String artifactId,
    String service1,
    String version1,
    String service2,
    String version2,
    String resolution
) {
}
