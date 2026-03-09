package com.flowforge.synthesis.model;

import java.util.Map;

/**
 * Estimated effort for the migration.
 */
public record EstimatedEffort(
    String totalDuration,
    int teamSize,
    String complexityRating,
    Map<String, String> perServiceEstimate
) {
}
