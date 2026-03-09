package com.flowforge.synthesis.model;

import java.util.List;

/**
 * Stage 3 output: migration risks, coupling points, and recommendations.
 */
public record RiskAssessmentOutput(
    String flowName,
    RiskLevel overallRisk,
    List<MigrationRisk> risks,
    List<CouplingPoint> couplingPoints,
    List<String> breakingChanges,
    List<String> recommendations
) {
    public enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
}
