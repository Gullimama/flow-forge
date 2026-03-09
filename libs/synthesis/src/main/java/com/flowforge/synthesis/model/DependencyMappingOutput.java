package com.flowforge.synthesis.model;

import java.util.List;

/**
 * Stage 4 output: runtime and build dependencies, conflicts, shared libraries.
 */
public record DependencyMappingOutput(
    String flowName,
    List<RuntimeDependency> runtimeDependencies,
    List<BuildDependency> buildDependencies,
    List<DependencyConflict> conflicts,
    List<SharedLibrary> sharedLibraries,
    DependencyGraph dependencyGraph
) {
}
