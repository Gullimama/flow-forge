package com.flowforge.publisher.model;

import java.util.List;
import java.util.Map;

/**
 * Aggregated risk matrix (entries and counts by severity/category).
 */
public record RiskMatrix(
    List<RiskMatrixEntry> entries,
    Map<String, Long> bySeverity,
    Map<String, Long> byCategory
) {
}
