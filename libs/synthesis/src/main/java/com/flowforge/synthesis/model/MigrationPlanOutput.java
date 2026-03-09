package com.flowforge.synthesis.model;

import java.util.List;

/**
 * Stage 5 output: migration strategy, phases, effort, rollback plan.
 */
public record MigrationPlanOutput(
    String flowName,
    MigrationPlanOutput.MigrationStrategy strategy,
    List<MigrationPhase> phases,
    List<String> prerequisites,
    EstimatedEffort effort,
    List<String> testingStrategy,
    RollbackPlan rollbackPlan
) {
    public enum MigrationStrategy {
        BIG_BANG, STRANGLER_FIG, PARALLEL_RUN, BRANCH_BY_ABSTRACTION
    }
}
