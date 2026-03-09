package com.flowforge.eval.pipeline;

import com.flowforge.eval.model.EvaluationResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EvaluationReport(
    UUID snapshotId,
    Instant timestamp,
    double avgPrecision,
    double avgRecall,
    double avgMRR,
    double avgSynthesisQuality,
    List<EvaluationResult.RetrievalEvaluation> retrievalResults,
    List<EvaluationResult.SynthesisEvaluation> synthesisResults,
    List<EvaluationResult.EmbeddingEvaluation> embeddingResults
) {}

