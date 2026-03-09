package com.flowforge.synthesis.model;

import java.util.List;

/**
 * One phase in the migration plan.
 */
public record MigrationPhase(
    int order,
    String phaseName,
    String description,
    List<String> services,
    List<String> tasks,
    List<String> deliverables,
    String estimatedDuration,
    List<String> risks
) {
}
