package com.flowforge.publisher.model;

/**
 * Single row in the risk matrix table.
 */
public record RiskMatrixEntry(
    String flowName,
    String riskDescription,
    String severity,
    String category,
    String mitigation
) {
}
