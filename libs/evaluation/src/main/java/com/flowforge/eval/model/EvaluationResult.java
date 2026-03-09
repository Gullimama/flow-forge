package com.flowforge.eval.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public sealed interface EvaluationResult {

    record RetrievalEvaluation(
        String queryId,
        int retrievedCount,
        int relevantCount,
        double precision,
        double recall,
        double f1,
        double mrr,
        double ndcg,
        Duration latency
    ) implements EvaluationResult {}

    record SynthesisEvaluation(
        String flowId,
        double factualConsistency,
        double completeness,
        double coherence,
        double technicalAccuracy,
        double overallScore,
        List<String> issues
    ) implements EvaluationResult {}

    record EmbeddingEvaluation(
        String collectionName,
        double intraCohesion,
        double interSeparation,
        double silhouetteScore,
        int testedPairs,
        Duration latency
    ) implements EvaluationResult {}

    record EndToEndEvaluation(
        UUID snapshotId,
        int totalFlows,
        int successfulFlows,
        double successRate,
        Duration totalLatency,
        Map<String, StageMetrics> perStageMetrics
    ) implements EvaluationResult {}

    record StageMetrics(
        String stageName,
        Duration latency,
        int inputCount,
        int outputCount,
        double qualityScore
    ) {}
}

