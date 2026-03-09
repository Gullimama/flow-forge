package com.flowforge.flow.model;

import java.util.List;
import java.util.UUID;

public record FlowCandidate(
    UUID candidateId,
    UUID snapshotId,
    String flowName,
    FlowType flowType,
    List<FlowStep> steps,
    List<String> involvedServices,
    FlowEvidence evidence,
    double confidenceScore,
    FlowComplexity complexity
) {
    public enum FlowType {
        SYNC_REQUEST,
        ASYNC_EVENT,
        MIXED,
        BATCH_PROCESS,
        ERROR_HANDLING
    }

    public enum FlowComplexity { LOW, MEDIUM, HIGH, VERY_HIGH }
}
