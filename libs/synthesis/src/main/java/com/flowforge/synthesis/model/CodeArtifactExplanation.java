package com.flowforge.synthesis.model;

import java.util.List;

/**
 * Explanation of a single code artifact (class/method) in the flow.
 */
public record CodeArtifactExplanation(
    String serviceName,
    String classFqn,
    String methodName,
    String purpose,
    String explanation,
    List<String> annotations,
    String complexityNote
) {
}
