package com.flowforge.publisher.model;

import com.flowforge.synthesis.model.CodeExplanationOutput;
import com.flowforge.synthesis.model.DependencyMappingOutput;
import com.flowforge.synthesis.model.FinalNarrativeOutput;
import com.flowforge.synthesis.model.FlowAnalysisOutput;
import com.flowforge.synthesis.model.MigrationPlanOutput;
import com.flowforge.synthesis.model.RiskAssessmentOutput;

/**
 * One flow's section in the research document (all 6 stage outputs).
 */
public record FlowSection(
    String flowName,
    String anchor,
    FlowAnalysisOutput analysis,
    CodeExplanationOutput codeExplanation,
    RiskAssessmentOutput riskAssessment,
    DependencyMappingOutput dependencyMapping,
    MigrationPlanOutput migrationPlan,
    FinalNarrativeOutput narrative
) {
}
