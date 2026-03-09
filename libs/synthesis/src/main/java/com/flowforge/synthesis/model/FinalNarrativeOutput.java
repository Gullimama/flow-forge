package com.flowforge.synthesis.model;

import java.util.List;

/**
 * Stage 6 output: executive summary, narrative, diagrams, key findings.
 */
public record FinalNarrativeOutput(
    String flowName,
    String executiveSummary,
    String detailedNarrative,
    List<DiagramSpec> diagrams,
    List<KeyFinding> keyFindings,
    List<String> openQuestions,
    String recommendedNextSteps
) {
}
