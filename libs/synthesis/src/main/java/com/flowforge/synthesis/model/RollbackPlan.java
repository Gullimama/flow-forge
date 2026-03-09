package com.flowforge.synthesis.model;

import java.util.List;

/**
 * Rollback strategy and steps.
 */
public record RollbackPlan(
    String strategy,
    List<String> rollbackSteps,
    String dataBackupApproach,
    String featureFlagUsage
) {
}
