package com.flowforge.synthesis.model;

import java.util.List;

/**
 * Stage 2 output: code artifacts and reactive patterns explanation.
 */
public record CodeExplanationOutput(
    String flowName,
    List<CodeArtifactExplanation> codeArtifacts,
    List<ReactivePatternExplanation> reactivePatterns,
    List<String> designPatterns,
    List<String> frameworkUsage
) {
}
