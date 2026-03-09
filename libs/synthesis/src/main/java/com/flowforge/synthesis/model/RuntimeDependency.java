package com.flowforge.synthesis.model;

/**
 * A runtime dependency (database, cache, broker, etc.) used by a service.
 */
public record RuntimeDependency(
    String serviceName,
    String dependencyName,
    String version,
    String purpose,
    RuntimeDependency.DependencyType type
) {
    public enum DependencyType {
        DATABASE, CACHE, MESSAGE_BROKER, EXTERNAL_API, CONFIG_SERVER, SERVICE_MESH
    }
}
