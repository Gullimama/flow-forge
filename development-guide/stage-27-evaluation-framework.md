# Stage 27 — Evaluation Framework (JUnit 5 + Custom Metrics)

## Goal

Build a comprehensive **evaluation framework** using JUnit 5 for assessing pipeline quality across all stages: retrieval precision/recall, synthesis quality, embedding coherence, and end-to-end pipeline correctness. The framework produces quantitative reports that feed into MLflow and qualify stage outputs for production readiness.

## Prerequisites

- Stage 18 (hybrid retrieval)
- Stage 21-22 (synthesis pipeline)
- Stage 26 (MLflow tracking)

## What to build

### 27.1 Evaluation domain model

```java
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
```

### 27.2 Retrieval evaluator

```java
@Component
public class RetrievalEvaluator {

    private final HybridRetrievalService retrieval;
    private final CrossEncoderReranker reranker;

    /**
     * Ground-truth evaluation dataset: query → set of relevant doc IDs.
     */
    public List<EvaluationResult.RetrievalEvaluation> evaluate(
            List<RetrievalTestCase> testCases) {
        return testCases.stream().map(tc -> {
            var start = Instant.now();
            var results = retrieval.retrieve(tc.query(), tc.topK());
            var latency = Duration.between(start, Instant.now());

            var retrievedIds = results.stream()
                .map(RetrievalResult::documentId)
                .collect(Collectors.toSet());

            var relevantRetrieved = retrievedIds.stream()
                .filter(tc.relevantIds()::contains)
                .count();

            double precision = retrievedIds.isEmpty() ? 0.0 :
                (double) relevantRetrieved / retrievedIds.size();
            double recall = tc.relevantIds().isEmpty() ? 0.0 :
                (double) relevantRetrieved / tc.relevantIds().size();
            double f1 = (precision + recall) == 0 ? 0.0 :
                2 * precision * recall / (precision + recall);

            double mrr = computeMRR(results, tc.relevantIds());
            double ndcg = computeNDCG(results, tc.relevantIds(), tc.topK());

            return new EvaluationResult.RetrievalEvaluation(
                tc.queryId(), retrievedIds.size(), (int) relevantRetrieved,
                precision, recall, f1, mrr, ndcg, latency
            );
        }).toList();
    }

    private double computeMRR(List<RetrievalResult> results, Set<String> relevant) {
        for (int i = 0; i < results.size(); i++) {
            if (relevant.contains(results.get(i).documentId())) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private double computeNDCG(List<RetrievalResult> results,
                                Set<String> relevant, int k) {
        double dcg = 0.0;
        double idcg = 0.0;
        for (int i = 0; i < Math.min(results.size(), k); i++) {
            double rel = relevant.contains(results.get(i).documentId()) ? 1.0 : 0.0;
            dcg += rel / (Math.log(i + 2) / Math.log(2));
        }
        for (int i = 0; i < Math.min(relevant.size(), k); i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0 ? 0.0 : dcg / idcg;
    }
}

public record RetrievalTestCase(
    String queryId,
    String query,
    Set<String> relevantIds,
    int topK
) {}
```

### 27.3 Synthesis evaluator

```java
@Component
public class SynthesisEvaluator {

    private final ChatModel chatModel;

    /**
     * Use an LLM-as-judge approach for synthesis quality evaluation.
     * Evaluates factual consistency, completeness, coherence, and technical accuracy.
     */
    public EvaluationResult.SynthesisEvaluation evaluate(
            FlowCandidate flow, FinalNarrativeOutput synthesis,
            List<RetrievalResult> sourceEvidence) {

        var issues = new ArrayList<String>();

        // 1. Factual consistency: check synthesis against source evidence
        double factualConsistency = evaluateFactualConsistency(
            synthesis.narrative(), sourceEvidence, issues);

        // 2. Completeness: check all flow steps are covered
        double completeness = evaluateCompleteness(
            flow, synthesis, issues);

        // 3. Coherence: logical flow and readability
        double coherence = evaluateCoherence(synthesis.narrative(), issues);

        // 4. Technical accuracy: correct Java/framework terminology
        double technicalAccuracy = evaluateTechnicalAccuracy(
            synthesis.narrative(), flow, issues);

        double overall = (factualConsistency * 0.3 + completeness * 0.25 +
                          coherence * 0.2 + technicalAccuracy * 0.25);

        return new EvaluationResult.SynthesisEvaluation(
            flow.flowId(), factualConsistency, completeness,
            coherence, technicalAccuracy, overall, issues
        );
    }

    private double evaluateFactualConsistency(String narrative,
            List<RetrievalResult> sources, List<String> issues) {
        var prompt = """
            You are evaluating factual consistency of a technical document.
            Rate how well the narrative is supported by the source evidence.

            NARRATIVE:
            %s

            SOURCE EVIDENCE (first 5):
            %s

            Return a JSON object: {"score": 0.0-1.0, "issues": ["..."]}
            """.formatted(narrative.substring(0, Math.min(2000, narrative.length())),
                          formatSources(sources.stream().limit(5).toList()));

        return callJudge(prompt, issues);
    }

    private double evaluateCompleteness(FlowCandidate flow,
            FinalNarrativeOutput synthesis, List<String> issues) {
        var coveredSteps = flow.steps().stream()
            .filter(step -> synthesis.narrative().contains(step.serviceName()))
            .count();
        double score = (double) coveredSteps / flow.steps().size();
        if (score < 0.8) {
            issues.add("Missing coverage for %d/%d flow steps"
                .formatted(flow.steps().size() - coveredSteps, flow.steps().size()));
        }
        return score;
    }

    private double evaluateCoherence(String narrative, List<String> issues) {
        // Check structural markers
        var sections = narrative.split("(?m)^#{1,3} ");
        double sectionScore = Math.min(1.0, sections.length / 5.0);

        // Check for logical connectors
        var connectors = List.of("therefore", "because", "as a result",
            "consequently", "this means", "next", "then", "finally");
        long connectorCount = connectors.stream()
            .filter(c -> narrative.toLowerCase().contains(c))
            .count();
        double connectorScore = Math.min(1.0, connectorCount / 4.0);

        return (sectionScore + connectorScore) / 2.0;
    }

    private double evaluateTechnicalAccuracy(String narrative,
            FlowCandidate flow, List<String> issues) {
        // Check for correct terminology
        var expectedTerms = new HashSet<String>();
        flow.steps().forEach(step -> {
            expectedTerms.add(step.serviceName());
            expectedTerms.addAll(step.annotations());
        });

        long found = expectedTerms.stream()
            .filter(narrative::contains)
            .count();

        return expectedTerms.isEmpty() ? 1.0 :
            (double) found / expectedTerms.size();
    }

    /**
     * Call LLM-as-judge for factual consistency scoring.
     *
     * IMPORTANT: For production use, configure a separate judge model
     * (e.g., GPT-4 or a different Qwen instance) to avoid circular
     * evaluation bias from judging with the same model that generated
     * the output. Set flowforge.eval.judge-model-url to point to a
     * distinct model endpoint.
     */
    private double callJudge(String prompt, List<String> issues) {
        try {
            var response = chatModel.call(new Prompt(prompt));
            var json = response.getResult().getOutput().getContent();
            // Strip markdown code fences if present
            json = json.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*$", "").trim();
            var node = new ObjectMapper().readTree(json);
            var judgeIssues = node.get("issues");
            if (judgeIssues != null && judgeIssues.isArray()) {
                judgeIssues.forEach(i -> issues.add(i.asText()));
            }
            return node.has("score") ? node.get("score").asDouble() : 0.5;
        } catch (Exception e) {
            log.warn("LLM judge call failed: {}", e.getMessage());
            issues.add("LLM judge unavailable: " + e.getMessage());
            return 0.5;
        }
    }
}
```

### 27.4 Embedding evaluator

```java
@Component
public class EmbeddingEvaluator {

    private final VectorStoreService vectorStore;
    private final EmbeddingModel embeddingModel;

    /**
     * Evaluate embedding quality by measuring cluster cohesion and separation.
     */
    public EvaluationResult.EmbeddingEvaluation evaluate(
            String collectionName, List<EmbeddingTestCase> testCases) {
        var start = Instant.now();

        // Embed test cases
        var embeddings = testCases.stream()
            .map(tc -> Map.entry(tc.label(), embeddingModel.embed(tc.text())))
            .toList();

        // Group by label
        var byLabel = embeddings.stream()
            .collect(Collectors.groupingBy(Map.Entry::getKey,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

        // Intra-cluster cohesion: mean cosine similarity within same label
        double intraCohesion = byLabel.values().stream()
            .filter(group -> group.size() > 1)
            .mapToDouble(group -> meanPairwiseSimilarity(group))
            .average().orElse(0.0);

        // Inter-cluster separation: mean cosine distance between different labels
        var labels = new ArrayList<>(byLabel.keySet());
        double interSeparation = 0.0;
        int pairs = 0;
        for (int i = 0; i < labels.size(); i++) {
            for (int j = i + 1; j < labels.size(); j++) {
                var group1 = byLabel.get(labels.get(i));
                var group2 = byLabel.get(labels.get(j));
                interSeparation += meanCrossGroupDistance(group1, group2);
                pairs++;
            }
        }
        interSeparation = pairs > 0 ? interSeparation / pairs : 0.0;

        // Standard silhouette coefficient: (b - a) / max(a, b)
        // where a = intra-cluster distance (1 - cohesion), b = inter-cluster distance
        double a = 1.0 - intraCohesion;
        double b = interSeparation;
        double silhouette = Math.max(a, b) == 0 ? 0.0 : (b - a) / Math.max(a, b);

        return new EvaluationResult.EmbeddingEvaluation(
            collectionName, intraCohesion, interSeparation, silhouette,
            testCases.size(), Duration.between(start, Instant.now())
        );
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}

public record EmbeddingTestCase(String label, String text) {}
```

### 27.5 End-to-end pipeline evaluator

```java
@Service
public class PipelineEvaluator {

    private final ExperimentTracker tracker;
    private final RetrievalEvaluator retrievalEval;
    private final SynthesisEvaluator synthesisEval;
    private final EmbeddingEvaluator embeddingEval;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Run full evaluation suite and log to MLflow.
     */
    public EvaluationReport runEvaluation(UUID snapshotId,
                                           EvaluationDataset dataset) {
        var params = Map.of(
            "snapshot_id", snapshotId.toString(),
            "eval_type", "full_pipeline",
            "retrieval_cases", String.valueOf(dataset.retrievalCases().size()),
            "synthesis_cases", String.valueOf(dataset.synthesisCases().size())
        );

        return tracker.trackRun("eval-" + snapshotId, params, ctx -> {
            // 1. Retrieval evaluation
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

            // 2. Embedding evaluation
            var embeddingResults = List.of(
                embeddingEval.evaluate("code-embeddings", dataset.codeEmbeddingCases()),
                embeddingEval.evaluate("log-embeddings", dataset.logEmbeddingCases())
            );
            for (var er : embeddingResults) {
                ctx.logMetric("embedding." + er.collectionName() + ".silhouette",
                              er.silhouetteScore());
            }
            ctx.logArtifact("eval/embedding_results.json", embeddingResults);

            // 3. Synthesis evaluation
            var synthesisResults = dataset.synthesisCases().stream()
                .map(sc -> synthesisEval.evaluate(sc.flow(), sc.synthesis(), sc.evidence()))
                .toList();
            var avgQuality = synthesisResults.stream()
                .mapToDouble(EvaluationResult.SynthesisEvaluation::overallScore)
                .average().orElse(0);

            ctx.logMetric("synthesis.quality", avgQuality);
            ctx.logArtifact("eval/synthesis_results.json", synthesisResults);

            // Build report
            var report = new EvaluationReport(
                snapshotId, Instant.now(),
                avgPrecision, avgRecall, avgMRR, avgQuality,
                retrievalResults, synthesisResults, embeddingResults
            );

            ctx.logArtifact("eval/full_report.json", report);
            return report;
        });
    }
}

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
```

### 27.6 JUnit 5 evaluation tests

```java
@SpringBootTest
@ActiveProfiles("eval")
@Tag("evaluation")
class PipelineEvaluationTest {

    @Autowired PipelineEvaluator evaluator;
    @Autowired EvaluationDatasetLoader datasetLoader;

    @Test
    @DisplayName("Retrieval precision ≥ 0.7")
    void retrievalPrecisionMeetsThreshold() {
        var dataset = datasetLoader.loadRetrievalDataset();
        var results = evaluator.runEvaluation(
            UUID.randomUUID(), dataset);
        assertThat(results.avgPrecision()).isGreaterThanOrEqualTo(0.7);
    }

    @Test
    @DisplayName("Retrieval recall ≥ 0.6")
    void retrievalRecallMeetsThreshold() {
        var dataset = datasetLoader.loadRetrievalDataset();
        var results = evaluator.runEvaluation(
            UUID.randomUUID(), dataset);
        assertThat(results.avgRecall()).isGreaterThanOrEqualTo(0.6);
    }

    @Test
    @DisplayName("Synthesis quality ≥ 0.65")
    void synthesisQualityMeetsThreshold() {
        var dataset = datasetLoader.loadSynthesisDataset();
        var results = evaluator.runEvaluation(
            UUID.randomUUID(), dataset);
        assertThat(results.avgSynthesisQuality()).isGreaterThanOrEqualTo(0.65);
    }

    @Test
    @DisplayName("Embedding silhouette ≥ 0.5")
    void embeddingSilhouetteMeetsThreshold() {
        var dataset = datasetLoader.loadEmbeddingDataset();
        var results = evaluator.runEvaluation(
            UUID.randomUUID(), dataset);
        results.embeddingResults().forEach(er ->
            assertThat(er.silhouetteScore()).isGreaterThanOrEqualTo(0.5)
        );
    }

    @Test
    @DisplayName("End-to-end latency < 5 minutes")
    void endToEndLatencyWithinBudget() {
        // Pipeline evaluation should complete in reasonable time
        var start = Instant.now();
        var dataset = datasetLoader.loadMinimalDataset();
        evaluator.runEvaluation(UUID.randomUUID(), dataset);
        var elapsed = Duration.between(start, Instant.now());
        assertThat(elapsed).isLessThan(Duration.ofMinutes(5));
    }
}
```

### 27.7 Dependencies

```kotlin
// libs/evaluation/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:retrieval"))
    implementation(project(":libs:synthesis"))
    implementation(project(":libs:embedding"))
    implementation(project(":libs:mlflow"))
    implementation(libs.spring.ai.openai)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
```

### AKS Deployment Context

This module is compiled into the FlowForge pipeline runner image (`flowforgeacr.azurecr.io/flowforge-pipeline:latest`) and executes as an Argo Workflow DAG task in the `flowforge` namespace (see Stage 28). It does not require its own Kubernetes Deployment.

**In-cluster service DNS names used:**

| Service | DNS | Port |
|---|---|---|
| PostgreSQL | `flowforge-pg-postgresql.flowforge-infra.svc.cluster.local` | 5432 |
| MinIO | `flowforge-minio.flowforge-infra.svc.cluster.local` | 9000 |
| OpenSearch | `flowforge-opensearch.flowforge-infra.svc.cluster.local` | 9200 |
| Qdrant | `flowforge-qdrant.flowforge-infra.svc.cluster.local` | 6334 |
| Neo4j | `flowforge-neo4j.flowforge-infra.svc.cluster.local` | 7687 |
| vLLM | `vllm.flowforge-ml.svc.cluster.local` | 8000 |
| MLflow | `mlflow.flowforge-obs.svc.cluster.local` | 5000 |

**Argo task resource class:** CPU (`cpupool` node selector) — evaluation runs that invoke vLLM do so via HTTP to the remote GPU pod.

---

## Testing & Verification Strategy

### 1. Unit Tests

All unit tests live in `libs/evaluation/src/test/java/com/flowforge/eval/`.

#### RetrievalEvaluatorTest

Validates precision, recall, F1, MRR, and NDCG computations against known result sets with hand-calculated expected values.

```java
@ExtendWith(MockitoExtension.class)
class RetrievalEvaluatorTest {

    @Mock HybridRetrievalService retrievalService;
    @Mock CrossEncoderReranker reranker;
    @InjectMocks RetrievalEvaluator evaluator;

    @Test
    @DisplayName("Precision: 3 relevant out of 5 retrieved = 0.6")
    void precision_threeOfFive() {
        var results = List.of(
            result("doc-1"), result("doc-2"), result("doc-3"),
            result("doc-4"), result("doc-5"));
        when(retrievalService.retrieve(anyString(), anyInt())).thenReturn(results);

        var testCase = new RetrievalTestCase(
            "q1", "test query", Set.of("doc-1", "doc-3", "doc-5"), 5);
        var eval = evaluator.evaluate(List.of(testCase)).getFirst();

        assertThat(eval.precision()).isCloseTo(0.6, within(0.001));
        assertThat(eval.relevantCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Recall: 3 found out of 10 relevant = 0.3")
    void recall_threeOfTen() {
        var results = List.of(result("doc-1"), result("doc-2"), result("doc-3"));
        when(retrievalService.retrieve(anyString(), anyInt())).thenReturn(results);

        var relevant = IntStream.rangeClosed(1, 10)
            .mapToObj(i -> "doc-" + i).collect(Collectors.toSet());
        var testCase = new RetrievalTestCase("q1", "test query", relevant, 10);
        var eval = evaluator.evaluate(List.of(testCase)).getFirst();

        assertThat(eval.recall()).isCloseTo(0.3, within(0.001));
    }

    @Test
    @DisplayName("F1: harmonic mean of precision and recall")
    void f1_harmonicMean() {
        var results = List.of(result("doc-1"), result("doc-2"));
        when(retrievalService.retrieve(anyString(), anyInt())).thenReturn(results);

        var testCase = new RetrievalTestCase(
            "q1", "query", Set.of("doc-1", "doc-3", "doc-4"), 5);
        var eval = evaluator.evaluate(List.of(testCase)).getFirst();

        double expectedP = 1.0 / 2.0;
        double expectedR = 1.0 / 3.0;
        double expectedF1 = 2 * expectedP * expectedR / (expectedP + expectedR);
        assertThat(eval.f1()).isCloseTo(expectedF1, within(0.001));
    }

    @Test
    @DisplayName("MRR: first relevant at position 3 = 1/3")
    void mrr_firstRelevantAtThird() {
        var results = List.of(result("doc-a"), result("doc-b"), result("doc-c"));
        when(retrievalService.retrieve(anyString(), anyInt())).thenReturn(results);

        var testCase = new RetrievalTestCase("q1", "query", Set.of("doc-c"), 5);
        var eval = evaluator.evaluate(List.of(testCase)).getFirst();

        assertThat(eval.mrr()).isCloseTo(1.0 / 3.0, within(0.001));
    }

    @Test
    @DisplayName("NDCG: perfect ranking yields 1.0")
    void ndcg_perfectRanking() {
        var results = List.of(result("r1"), result("r2"), result("r3"));
        when(retrievalService.retrieve(anyString(), anyInt())).thenReturn(results);

        var testCase = new RetrievalTestCase(
            "q1", "query", Set.of("r1", "r2", "r3"), 3);
        var eval = evaluator.evaluate(List.of(testCase)).getFirst();

        assertThat(eval.ndcg()).isCloseTo(1.0, within(0.001));
    }

    @Test
    @DisplayName("Empty result set yields all-zero metrics")
    void emptyResults_allZero() {
        when(retrievalService.retrieve(anyString(), anyInt())).thenReturn(List.of());

        var testCase = new RetrievalTestCase("q1", "query", Set.of("doc-1"), 5);
        var eval = evaluator.evaluate(List.of(testCase)).getFirst();

        assertThat(eval.precision()).isZero();
        assertThat(eval.recall()).isZero();
        assertThat(eval.f1()).isZero();
        assertThat(eval.mrr()).isZero();
    }

    private RetrievalResult result(String docId) {
        return new RetrievalResult(docId, 0.9, "snippet");
    }
}
```

#### SynthesisEvaluatorTest

Tests LLM-as-judge invocation, markdown fence stripping, and component scoring (completeness, coherence, technical accuracy).

```java
@ExtendWith(MockitoExtension.class)
class SynthesisEvaluatorTest {

    @Mock ChatModel chatModel;
    @InjectMocks SynthesisEvaluator evaluator;

    @Test
    @DisplayName("callJudge strips markdown code fences from LLM response")
    void callJudge_stripsMarkdownFences() {
        var response = mockChatResponse("""
            ```json
            {"score": 0.85, "issues": ["minor gap in coverage"]}
            ```
            """);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        var flow = testFlow(List.of("ServiceA", "ServiceB"));
        var synthesis = testSynthesis("# Overview\nServiceA calls ServiceB therefore...");
        var eval = evaluator.evaluate(flow, synthesis, List.of());

        assertThat(eval.factualConsistency()).isCloseTo(0.85, within(0.01));
    }

    @Test
    @DisplayName("Completeness: all steps mentioned yields score 1.0")
    void completeness_allStepsCovered() {
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(mockChatResponse("{\"score\": 0.8, \"issues\": []}"));

        var flow = testFlow(List.of("OrderService", "PaymentService"));
        var synthesis = testSynthesis(
            "# Flow\nOrderService receives the request then PaymentService processes payment");

        var eval = evaluator.evaluate(flow, synthesis, List.of());
        assertThat(eval.completeness()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Completeness: missing steps reduces score below 0.8")
    void completeness_missingSteps() {
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(mockChatResponse("{\"score\": 0.5, \"issues\": []}"));

        var flow = testFlow(List.of("A", "B", "C", "D", "E"));
        var synthesis = testSynthesis("Only A and B are mentioned.");

        var eval = evaluator.evaluate(flow, synthesis, List.of());
        assertThat(eval.completeness()).isCloseTo(0.4, within(0.01));
        assertThat(eval.issues()).anyMatch(i -> i.contains("Missing coverage"));
    }

    @Test
    @DisplayName("callJudge returns 0.5 when LLM is unavailable")
    void callJudge_fallbackOnError() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));

        var eval = evaluator.evaluate(testFlow(List.of("Svc")),
            testSynthesis("narrative"), List.of());

        assertThat(eval.factualConsistency()).isEqualTo(0.5);
        assertThat(eval.issues()).anyMatch(i -> i.contains("LLM judge unavailable"));
    }

    @Test
    @DisplayName("Coherence: sections and logical connectors boost score")
    void coherence_goodStructure() {
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(mockChatResponse("{\"score\": 0.9, \"issues\": []}"));

        var narrative = """
            # Overview
            The system processes requests.
            ## Step 1
            Therefore, OrderService handles the order.
            ## Step 2
            As a result, PaymentService charges the card.
            ## Step 3
            Consequently, NotificationService sends confirmation.
            ## Step 4
            Finally, the flow completes.
            """;
        var eval = evaluator.evaluate(testFlow(List.of("OrderService")),
            testSynthesis(narrative), List.of());

        assertThat(eval.coherence()).isGreaterThan(0.5);
    }
}
```

#### EmbeddingEvaluatorTest

Validates cosine similarity, silhouette score computation with the `(b - a) / max(a, b)` formula, and edge cases.

```java
@ExtendWith(MockitoExtension.class)
class EmbeddingEvaluatorTest {

    @Mock VectorStoreService vectorStore;
    @Mock EmbeddingModel embeddingModel;
    @InjectMocks EmbeddingEvaluator evaluator;

    @Test
    @DisplayName("Cosine similarity of identical vectors is 1.0")
    void cosineSimilarity_identical() {
        float[] vec = {1.0f, 2.0f, 3.0f};
        when(embeddingModel.embed(anyString())).thenReturn(vec);

        var testCases = List.of(
            new EmbeddingTestCase("cluster-a", "text1"),
            new EmbeddingTestCase("cluster-a", "text2"));
        var eval = evaluator.evaluate("test-collection", testCases);

        assertThat(eval.intraCohesion()).isCloseTo(1.0, within(0.001));
    }

    @Test
    @DisplayName("Silhouette score: well-separated clusters yield positive score")
    void silhouetteScore_wellSeparated() {
        when(embeddingModel.embed(contains("java")))
            .thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        when(embeddingModel.embed(contains("python")))
            .thenReturn(new float[]{-1.0f, 0.0f, 0.0f});

        var testCases = List.of(
            new EmbeddingTestCase("java", "java code"),
            new EmbeddingTestCase("java", "java class"),
            new EmbeddingTestCase("python", "python script"),
            new EmbeddingTestCase("python", "python module"));
        var eval = evaluator.evaluate("code-embeddings", testCases);

        assertThat(eval.silhouetteScore()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("Silhouette formula: (b - a) / max(a, b) with a=intra-distance, b=inter-distance")
    void silhouetteFormula_correctComputation() {
        when(embeddingModel.embed("same1")).thenReturn(new float[]{1.0f, 0.0f});
        when(embeddingModel.embed("same2")).thenReturn(new float[]{0.9f, 0.1f});
        when(embeddingModel.embed("diff1")).thenReturn(new float[]{-1.0f, 0.0f});
        when(embeddingModel.embed("diff2")).thenReturn(new float[]{-0.9f, -0.1f});

        var testCases = List.of(
            new EmbeddingTestCase("A", "same1"),
            new EmbeddingTestCase("A", "same2"),
            new EmbeddingTestCase("B", "diff1"),
            new EmbeddingTestCase("B", "diff2"));
        var eval = evaluator.evaluate("test", testCases);

        double a = 1.0 - eval.intraCohesion();
        double b = eval.interSeparation();
        double expected = (b - a) / Math.max(a, b);
        assertThat(eval.silhouetteScore()).isCloseTo(expected, within(0.001));
    }

    @Test
    @DisplayName("Single-item clusters yield 0 intraCohesion")
    void singleItemClusters_zeroCohesion() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});

        var testCases = List.of(
            new EmbeddingTestCase("A", "text1"),
            new EmbeddingTestCase("B", "text2"));
        var eval = evaluator.evaluate("test", testCases);

        assertThat(eval.intraCohesion()).isZero();
    }
}
```

#### PipelineEvaluatorTest

Tests the orchestration of all sub-evaluators and MLflow metric logging.

```java
@ExtendWith(MockitoExtension.class)
class PipelineEvaluatorTest {

    @Mock ExperimentTracker tracker;
    @Mock RetrievalEvaluator retrievalEval;
    @Mock SynthesisEvaluator synthesisEval;
    @Mock EmbeddingEvaluator embeddingEval;
    @Mock MeterRegistry meterRegistry;
    @Mock ObjectMapper objectMapper;
    @InjectMocks PipelineEvaluator evaluator;

    @Test
    @DisplayName("runEvaluation delegates to all sub-evaluators and returns report")
    void runEvaluation_delegatesToAllEvaluators() {
        when(tracker.trackRun(anyString(), anyMap(), any())).thenAnswer(inv -> {
            ExperimentTracker.TrainingFunction<?> fn = inv.getArgument(2);
            var ctx = mock(ExperimentTracker.TrainingContext.class);
            return fn.train(ctx);
        });
        when(retrievalEval.evaluate(anyList())).thenReturn(List.of(
            new EvaluationResult.RetrievalEvaluation(
                "q1", 5, 3, 0.6, 0.3, 0.4, 0.33, 0.7, Duration.ofMillis(50))));
        when(embeddingEval.evaluate(anyString(), anyList())).thenReturn(
            new EvaluationResult.EmbeddingEvaluation(
                "code", 0.8, 0.6, 0.5, 10, Duration.ofMillis(100)));

        var dataset = minimalDataset();
        var report = evaluator.runEvaluation(UUID.randomUUID(), dataset);

        assertThat(report).isNotNull();
        assertThat(report.avgPrecision()).isCloseTo(0.6, within(0.01));
        verify(retrievalEval).evaluate(anyList());
        verify(embeddingEval, atLeast(1)).evaluate(anyString(), anyList());
    }
}
```

### 2. Integration Tests

#### Threshold evaluation with Spring context

Validates that evaluation metrics meet minimum quality thresholds using a curated ground-truth dataset.

```java
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class EvaluationThresholdIntegrationTest {

    @Autowired RetrievalEvaluator retrievalEval;
    @MockitoBean HybridRetrievalService retrievalService;
    @MockitoBean CrossEncoderReranker reranker;

    @Test
    @DisplayName("Retrieval metrics computed correctly with curated dataset")
    void retrievalMetrics_curatedDataset() {
        when(retrievalService.retrieve(eq("how does OrderService work?"), anyInt()))
            .thenReturn(List.of(
                new RetrievalResult("doc-order-1", 0.95, "OrderService"),
                new RetrievalResult("doc-order-2", 0.85, "OrderService impl"),
                new RetrievalResult("doc-payment-1", 0.60, "PaymentService")));

        var testCase = new RetrievalTestCase("q1",
            "how does OrderService work?",
            Set.of("doc-order-1", "doc-order-2"), 5);

        var results = retrievalEval.evaluate(List.of(testCase));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().precision()).isGreaterThanOrEqualTo(0.5);
        assertThat(results.getFirst().mrr()).isEqualTo(1.0);
    }
}
```

### 3. Test Fixtures & Sample Data

| Fixture file | Description |
|---|---|
| `src/test/resources/eval-datasets/retrieval-ground-truth.json` | 20+ query-to-relevant-doc-ID mappings for retrieval evaluation |
| `src/test/resources/eval-datasets/synthesis-cases.json` | 5 flow candidates with pre-generated syntheses and source evidence |
| `src/test/resources/eval-datasets/embedding-test-cases.json` | 30+ labeled text snippets across 5 clusters (Java, Python, log-error, log-info, config) |
| `src/test/resources/eval-datasets/llm-judge-response.json` | Canned LLM judge responses for deterministic synthesis tests |
| `src/test/resources/eval-datasets/minimal-dataset.json` | Minimal dataset with 2 retrieval cases, 1 synthesis case, 2 embedding groups for fast CI |

### 4. Mocking Strategy

| Dependency | Strategy | Rationale |
|---|---|---|
| `HybridRetrievalService` | **Mockito** (`@Mock`) | Retrieval depends on OpenSearch + Qdrant; return predetermined results |
| `CrossEncoderReranker` | **Mockito** (`@Mock`) | Avoid loading DJL ONNX model in tests |
| `ChatModel` (LLM judge) | **Mockito** (`@Mock`) | External LLM calls are non-deterministic; return canned JSON responses |
| `EmbeddingModel` | **Mockito** (`@Mock`) returning fixed vectors | Control vector values for deterministic silhouette computation |
| `ExperimentTracker` | **Mockito** in `PipelineEvaluatorTest` | Avoid MLflow HTTP calls; verify metric logging via `verify()` |
| `VectorStoreService` | **Mockito** (`@Mock`) | Not directly used in evaluation math — only in embedding evaluator |
| `ObjectMapper` | **Real instance** | JSON parsing in judge response handling must work correctly |

### 5. CI/CD Considerations

- **Test tags**: `@Tag("unit")` for pure metric computation tests, `@Tag("integration")` for Spring context tests, `@Tag("evaluation")` for full threshold tests
- **Evaluation tests are optional in PR builds**: Run `@Tag("evaluation")` tests only in nightly builds since they depend on curated datasets
- **Gradle task separation**:
  ```kotlin
  tasks.test { useJUnitPlatform { includeTags("unit") } }
  tasks.register<Test>("integrationTest") { useJUnitPlatform { includeTags("integration") } }
  tasks.register<Test>("evaluationTest") { useJUnitPlatform { includeTags("evaluation") } }
  ```
- **No Docker required**: All tests use mocked dependencies — no external services needed
- **LLM judge determinism**: Always mock `ChatModel` in CI to avoid non-deterministic scores; use canned responses from `llm-judge-response.json`
- **Threshold regression**: Store expected minimum thresholds in `application-test.yml` so they can be tuned without code changes:
  ```yaml
  flowforge.eval.thresholds:
    retrieval-precision: 0.7
    retrieval-recall: 0.6
    synthesis-quality: 0.65
    embedding-silhouette: 0.5
  ```

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| Precision computation | Known test set (3/5 relevant) | precision = 0.6 |
| Recall computation | Known test set (3/10 found) | recall = 0.3 |
| MRR computation | First relevant at position 3 | MRR = 0.333 |
| NDCG computation | Known ranked list | Matches expected NDCG |
| Cosine similarity | Identical vectors | similarity = 1.0 |
| Silhouette score | Well-separated clusters | score > 0.5 |
| Synthesis completeness | All steps mentioned | score = 1.0 |
| LLM judge | Factual narrative | score > 0.7 |
| MLflow logging | Run evaluation | All metrics in MLflow |
| JUnit threshold | Run tests | All assertions pass |
| Report generation | Full evaluation | JSON report written |
| E2E latency | Full pipeline | Under 5 minutes |

## Files to create

- `libs/evaluation/build.gradle.kts`
- `libs/evaluation/src/main/java/com/flowforge/eval/model/EvaluationResult.java`
- `libs/evaluation/src/main/java/com/flowforge/eval/retrieval/RetrievalEvaluator.java`
- `libs/evaluation/src/main/java/com/flowforge/eval/synthesis/SynthesisEvaluator.java`
- `libs/evaluation/src/main/java/com/flowforge/eval/embedding/EmbeddingEvaluator.java`
- `libs/evaluation/src/main/java/com/flowforge/eval/pipeline/PipelineEvaluator.java`
- `libs/evaluation/src/main/java/com/flowforge/eval/model/EvaluationReport.java`
- `libs/evaluation/src/test/java/.../PipelineEvaluationTest.java`
- `libs/evaluation/src/test/resources/eval-datasets/*.json`

## Depends on

- Stage 18 (hybrid retrieval for evaluating)
- Stage 21-22 (synthesis pipeline for evaluating)
- Stage 26 (MLflow for logging evaluation results)

## Produces

- Quantitative retrieval metrics (precision, recall, MRR, NDCG)
- Synthesis quality scores (factual consistency, completeness, coherence)
- Embedding quality scores (cohesion, separation, silhouette)
- Full evaluation report logged to MLflow
- JUnit 5 threshold tests for CI/CD quality gates
