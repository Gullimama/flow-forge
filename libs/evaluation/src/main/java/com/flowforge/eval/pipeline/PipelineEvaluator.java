package com.flowforge.eval.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.eval.embedding.EmbeddingEvaluator;
import com.flowforge.eval.embedding.EmbeddingTestCase;
import com.flowforge.eval.model.EvaluationResult;
import com.flowforge.eval.retrieval.RetrievalEvaluator;
import com.flowforge.eval.retrieval.RetrievalTestCase;
import com.flowforge.eval.synthesis.SynthesisEvaluator;
import com.flowforge.mlflow.service.ExperimentTracker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PipelineEvaluator {

    private final ExperimentTracker tracker;
    private final RetrievalEvaluator retrievalEval;
    private final SynthesisEvaluator synthesisEval;
    private final EmbeddingEvaluator embeddingEval;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public PipelineEvaluator(
        ExperimentTracker tracker,
        RetrievalEvaluator retrievalEval,
        SynthesisEvaluator synthesisEval,
        EmbeddingEvaluator embeddingEval,
        MeterRegistry meterRegistry,
        ObjectMapper objectMapper
    ) {
        this.tracker = tracker;
        this.retrievalEval = retrievalEval;
        this.synthesisEval = synthesisEval;
        this.embeddingEval = embeddingEval;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    public EvaluationReport runEvaluation(UUID snapshotId, EvaluationDataset dataset) {
        var params = Map.of(
            "snapshot_id", snapshotId.toString(),
            "eval_type", "full_pipeline",
            "retrieval_cases", String.valueOf(dataset.retrievalCases().size()),
            "synthesis_cases", String.valueOf(dataset.synthesisCases().size())
        );

        return tracker.trackRun("eval-" + snapshotId, params, ctx -> {
            var retrievalResults = retrievalEval.evaluate(dataset.retrievalCases());
            var avgPrecision = retrievalResults.stream()
                .mapToDouble(EvaluationResult.RetrievalEvaluation::precision)
                .average().orElse(0);
            var avgRecall = retrievalResults.stream()
                .mapToDouble(EvaluationResult.RetrievalEvaluation::recall)
                .average().orElse(0);
            var avgMRR = retrievalResults.stream()
                .mapToDouble(EvaluationResult.RetrievalEvaluation::mrr)
                .average().orElse(0);

            ctx.logMetric("retrieval.precision", avgPrecision);
            ctx.logMetric("retrieval.recall", avgRecall);
            ctx.logMetric("retrieval.mrr", avgMRR);
            ctx.logArtifact("eval/retrieval_results.json", retrievalResults);

            var embeddingResults = List.of(
                embeddingEval.evaluate("code-embeddings", dataset.codeEmbeddingCases()),
                embeddingEval.evaluate("log-embeddings", dataset.logEmbeddingCases())
            );
            for (var er : embeddingResults) {
                ctx.logMetric("embedding." + er.collectionName() + ".silhouette",
                    er.silhouetteScore());
            }
            ctx.logArtifact("eval/embedding_results.json", embeddingResults);

            var synthesisResults = dataset.synthesisCases().stream()
                .map(sc -> synthesisEval.evaluate(sc.flow(), sc.synthesis(), sc.evidence()))
                .toList();
            var avgQuality = synthesisResults.stream()
                .mapToDouble(EvaluationResult.SynthesisEvaluation::overallScore)
                .average().orElse(0);

            ctx.logMetric("synthesis.quality", avgQuality);
            ctx.logArtifact("eval/synthesis_results.json", synthesisResults);

            var report = new EvaluationReport(
                snapshotId, Instant.now(),
                avgPrecision, avgRecall, avgMRR, avgQuality,
                retrievalResults, synthesisResults, embeddingResults
            );

            ctx.logArtifact("eval/full_report.json", report);
            meterRegistry.counter("flowforge.eval.runs").increment();
            return report;
        });
    }
}


