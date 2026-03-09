package com.flowforge.synthesis.model;

import java.util.List;

/**
 * Input → transformations → output and side effects.
 */
public record DataFlowDescription(
    String inputData,
    String outputData,
    List<String> transformations,
    List<String> sideEffects
) {
}
