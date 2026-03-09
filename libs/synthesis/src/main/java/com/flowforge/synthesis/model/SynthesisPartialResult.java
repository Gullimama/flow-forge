package com.flowforge.synthesis.model;

import java.util.UUID;

/**
 * Result of running synthesis stages 1–3 for one flow candidate.
 */
public record SynthesisPartialResult(
    UUID candidateId,
    FlowAnalysisOutput flowAnalysis,
    CodeExplanationOutput codeExplanation,
    RiskAssessmentOutput riskAssessment
) {
}
