package com.flowforge.synthesis.model;

import java.util.List;

/**
 * Shared internal library and its migration impact.
 */
public record SharedLibrary(
    String name,
    String version,
    List<String> consumers,
    String migrationImpact
) {
}
