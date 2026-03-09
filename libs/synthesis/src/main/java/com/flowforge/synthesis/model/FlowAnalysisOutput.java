package com.flowforge.synthesis.model;

import java.util.List;

/**
 * Stage 1 output: flow purpose, interactions, and data flow description.
 */
public record FlowAnalysisOutput(
    String flowName,
    String purpose,
    String triggerDescription,
    List<InteractionStep> interactions,
    DataFlowDescription dataFlow,
    List<String> externalDependencies,
    List<String> assumptions
) {
}
