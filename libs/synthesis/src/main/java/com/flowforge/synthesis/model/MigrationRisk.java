package com.flowforge.synthesis.model;

/**
 * A single migration risk (category, severity, mitigation).
 */
public record MigrationRisk(
    String category,
    String description,
    RiskAssessmentOutput.RiskLevel severity,
    String affectedService,
    String mitigation
) {
}
