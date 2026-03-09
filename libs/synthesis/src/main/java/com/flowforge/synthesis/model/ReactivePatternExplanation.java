package com.flowforge.synthesis.model;

/**
 * Explanation of a reactive pattern and migration implication.
 */
public record ReactivePatternExplanation(
    String location,
    String reactiveChain,
    String explanation,
    String complexity,
    String migrationImplication
) {
}
