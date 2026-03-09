package com.flowforge.synthesis.model;

import java.util.UUID;

/**
 * Result of running all 6 synthesis stages for one flow candidate.
 */
public record SynthesisFullResult(
    UUID candidateId,
    SynthesisPartialResult stages1to3,
    DependencyMappingOutput dependencyMapping,
    MigrationPlanOutput migrationPlan,
    FinalNarrativeOutput narrative
) {
}
