package com.flowforge.synthesis.model;

/**
 * A Maven/Gradle build dependency.
 */
public record BuildDependency(
    String serviceName,
    String groupId,
    String artifactId,
    String version,
    String scope
) {
}
