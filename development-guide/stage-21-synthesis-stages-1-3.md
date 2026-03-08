# Stage 21 — Synthesis Stages 1–3 (Flow Analysis, Code Explanation, Risk Assessment)

## Goal

Implement the first three LLM synthesis stages that analyze each flow candidate:
1. **Flow Analysis** — Describe the flow's purpose, interactions, and data flow
2. **Code Explanation** — Explain the key code artifacts in each step
3. **Risk Assessment** — Identify migration risks, reactive complexity, and coupling

Each stage uses Spring AI `ChatModel` with `BeanOutputConverter` for structured JSON output.

## Prerequisites

- Stage 19 (flow candidates with evidence)
- Stage 20 (LLM generation service)

## What to build

### 21.1 Synthesis stage output records

```java
// Stage 1: Flow Analysis
public record FlowAnalysisOutput(
    String flowName,
    String purpose,
    String triggerDescription,
    List<InteractionStep> interactions,
    DataFlowDescription dataFlow,
    List<String> externalDependencies,
    List<String> assumptions
) {}

public record InteractionStep(
    int order,
    String fromService,
    String toService,
    String protocol,           // HTTP, Kafka, gRPC
    String description,
    String dataExchanged
) {}

public record DataFlowDescription(
    String inputData,
    String outputData,
    List<String> transformations,
    List<String> sideEffects
) {}

// Stage 2: Code Explanation
public record CodeExplanationOutput(
    String flowName,
    List<CodeArtifactExplanation> codeArtifacts,
    List<ReactivePatternExplanation> reactivePatterns,
    List<String> designPatterns,
    List<String> frameworkUsage
) {}

public record CodeArtifactExplanation(
    String serviceName,
    String classFqn,
    String methodName,
    String purpose,
    String explanation,
    List<String> annotations,
    String complexityNote
) {}

public record ReactivePatternExplanation(
    String location,
    String reactiveChain,
    String explanation,
    ReactiveComplexity complexity,
    String migrationImplication
) {}

// Stage 3: Risk Assessment
public record RiskAssessmentOutput(
    String flowName,
    RiskLevel overallRisk,
    List<MigrationRisk> risks,
    List<CouplingPoint> couplingPoints,
    List<String> breakingChanges,
    List<String> recommendations
) {
    public enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
}

public record MigrationRisk(
    String category,           // REACTIVE, COUPLING, STATE, CONFIGURATION, etc.
    String description,
    RiskAssessmentOutput.RiskLevel severity,
    String affectedService,
    String mitigation
) {}

public record CouplingPoint(
    String service1,
    String service2,
    String couplingType,       // SYNC_HTTP, SHARED_DB, SHARED_CONFIG, etc.
    String description,
    String decouplingStrategy
) {}
```

### 21.2 Synthesis stage executor

```java
@Service
public class SynthesisStageExecutor {

    private final LlmGenerationService llm;
    private final HybridRetrievalService retrieval;
    private final MeterRegistry meterRegistry;

    /**
     * Execute a synthesis stage for a single flow candidate.
     */
    public <T> T executeStage(String stageName, FlowCandidate candidate,
                               Class<T> outputType) {
        return executeStage(stageName, candidate, outputType, Map.of());
    }

    /**
     * Execute a synthesis stage with prior stage outputs injected into the prompt context.
     */
    public <T> T executeStage(String stageName, FlowCandidate candidate,
                               Class<T> outputType,
                               Map<String, Object> priorStageOutputs) {
        // 1. Build context from evidence
        var context = buildContext(candidate);

        // 2. Inject prior stage outputs so the LLM sees the full chain
        context.putAll(priorStageOutputs);

        // 3. Augment with additional retrieval if needed
        var additionalContext = retrieveAdditionalContext(stageName, candidate);
        context.putAll(additionalContext);

        // 4. Generate structured output (retry on JSON parse failure)
        return generateWithRetry(stageName, context, outputType, 2);
    }

    /**
     * Retry LLM generation up to maxRetries on JSON parse failures.
     */
    private <T> T generateWithRetry(String stageName, Map<String, Object> context,
                                     Class<T> outputType, int maxRetries) {
        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return llm.generateStructured("synthesis-" + stageName, context, outputType);
            } catch (Exception e) {
                lastException = e;
                log.warn("Synthesis stage {} attempt {} failed: {}", stageName, attempt + 1, e.getMessage());
            }
        }
        throw new RuntimeException("Synthesis stage %s failed after %d attempts"
            .formatted(stageName, maxRetries + 1), lastException);
    }

    private Map<String, Object> buildContext(FlowCandidate candidate) {
        var context = new HashMap<String, Object>();
        context.put("flowName", candidate.flowName());
        context.put("flowType", candidate.flowType().name());
        context.put("services", String.join(", ", candidate.involvedServices()));
        context.put("steps", formatSteps(candidate.steps()));
        context.put("codeEvidence", String.join("\n---\n", candidate.evidence().codeSnippets()));
        context.put("logPatterns", String.join("\n", candidate.evidence().logPatterns()));
        context.put("graphContext", String.join("\n", candidate.evidence().graphPaths()));
        context.put("complexity", candidate.complexity().name());
        return context;
    }

    private Map<String, Object> retrieveAdditionalContext(String stageName, FlowCandidate candidate) {
        return Map.of();
    }

    private String formatSteps(List<FlowStep> steps) {
        return steps.stream()
            .map(s -> "%s → %s %s (%s)".formatted(
                s.serviceName(), s.httpMethod(), s.path(), s.stepType()))
            .collect(Collectors.joining("\n"));
    }
}
```

### 21.3 Stage 1: Flow analysis

```java
@Component
public class FlowAnalysisStage {

    private final SynthesisStageExecutor executor;
    private final MinioStorageClient minio;

    /**
     * Analyze a flow candidate: purpose, interactions, data flow.
     */
    public FlowAnalysisOutput analyze(FlowCandidate candidate) {
        var output = executor.executeStage("stage1", candidate, FlowAnalysisOutput.class);

        // Store intermediate result
        minio.putJson("evidence",
            "synthesis/stage1/%s/%s.json".formatted(
                candidate.snapshotId(), candidate.candidateId()),
            output);

        return output;
    }
}
```

### 21.4 Stage 2: Code explanation

```java
@Component
public class CodeExplanationStage {

    private final SynthesisStageExecutor executor;
    private final MinioStorageClient minio;

    /**
     * Explain code artifacts in each flow step.
     */
    public CodeExplanationOutput explain(FlowCandidate candidate,
                                          FlowAnalysisOutput flowAnalysis) {
        var output = executor.executeStage("stage2", candidate,
            CodeExplanationOutput.class,
            Map.of("priorStageOutput", serializeToString(flowAnalysis)));

        minio.putJson("evidence",
            "synthesis/stage2/%s/%s.json".formatted(
                candidate.snapshotId(), candidate.candidateId()),
            output);

        return output;
    }
}
```

### 21.5 Stage 3: Risk assessment

```java
@Component
public class RiskAssessmentStage {

    private final SynthesisStageExecutor executor;
    private final MinioStorageClient minio;

    /**
     * Assess migration risks for the flow.
     */
    public RiskAssessmentOutput assess(FlowCandidate candidate,
                                        FlowAnalysisOutput flowAnalysis,
                                        CodeExplanationOutput codeExplanation) {
        var output = executor.executeStage("stage3", candidate,
            RiskAssessmentOutput.class,
            Map.of("flowAnalysis", serializeToString(flowAnalysis),
                   "codeExplanation", serializeToString(codeExplanation)));

        minio.putJson("evidence",
            "synthesis/stage3/%s/%s.json".formatted(
                candidate.snapshotId(), candidate.candidateId()),
            output);

        return output;
    }
}
```

### 21.6 Stage 1-3 orchestrator

```java
@Service
public class SynthesisStages1To3Service {

    private final FlowAnalysisStage stage1;
    private final CodeExplanationStage stage2;
    private final RiskAssessmentStage stage3;
    private final MeterRegistry meterRegistry;

    /**
     * Run stages 1-3 sequentially for a flow candidate (each depends on prior).
     */
    public SynthesisPartialResult runStages1To3(FlowCandidate candidate) {
        // Stage 1: Flow Analysis
        var flowAnalysis = meterRegistry.timer("flowforge.synthesis.stage1.latency")
            .record(() -> stage1.analyze(candidate));

        // Stage 2: Code Explanation (uses Stage 1 output)
        var codeExplanation = meterRegistry.timer("flowforge.synthesis.stage2.latency")
            .record(() -> stage2.explain(candidate, flowAnalysis));

        // Stage 3: Risk Assessment (uses Stage 1 + 2 output)
        var riskAssessment = meterRegistry.timer("flowforge.synthesis.stage3.latency")
            .record(() -> stage3.assess(candidate, flowAnalysis, codeExplanation));

        return new SynthesisPartialResult(
            candidate.candidateId(),
            flowAnalysis,
            codeExplanation,
            riskAssessment
        );
    }

    /**
     * Run stages 1-3 for ALL flow candidates.
     * Flows are independent of each other, so we parallelize across flows
     * using virtual threads while keeping stages sequential within each flow.
     */
    public List<SynthesisPartialResult> runAllCandidates(List<FlowCandidate> candidates) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = candidates.stream()
                .map(c -> executor.submit(() -> runStages1To3(c)))
                .toList();
            return futures.stream()
                .map(f -> {
                    try {
                        return f.get();
                    } catch (Exception e) {
                        log.error("Synthesis failed for candidate: {}", e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
        }
    }
}

public record SynthesisPartialResult(
    UUID candidateId,
    FlowAnalysisOutput flowAnalysis,
    CodeExplanationOutput codeExplanation,
    RiskAssessmentOutput riskAssessment
) {}
```

### 21.7 Dependencies

```kotlin
// libs/synthesis/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:llm"))
    implementation(project(":libs:retrieval"))
    implementation(project(":libs:flow-builder"))
}
```

### AKS Deployment Context

This module is compiled into the FlowForge pipeline runner image (`flowforgeacr.azurecr.io/flowforge-pipeline:latest`) and executes as an Argo Workflow DAG task in the `flowforge` namespace (see Stage 28). It does not require its own Kubernetes Deployment.

**In-cluster service DNS names used:**

| Service | DNS | Port |
|---|---|---|
| Neo4j | `flowforge-neo4j.flowforge-infra.svc.cluster.local` | 7687 |
| Qdrant | `flowforge-qdrant.flowforge-infra.svc.cluster.local` | 6334 |
| OpenSearch | `flowforge-opensearch.flowforge-infra.svc.cluster.local` | 9200 |
| vLLM | `vllm.flowforge-ml.svc.cluster.local` | 8000 |

**Argo task resource class:** CPU (`cpupool` node selector) — vLLM inference runs on its own GPU pod.

---

## Testing & Verification Strategy

### Unit Tests

**`SynthesisStageExecutorTest`** — validates context building, retry logic, and prior-stage injection.

```java
@ExtendWith(MockitoExtension.class)
class SynthesisStageExecutorTest {

    @Mock LlmGenerationService llm;
    @Mock HybridRetrievalService retrieval;
    @Mock MeterRegistry meterRegistry;

    @InjectMocks SynthesisStageExecutor executor;

    @Test
    void executeStage_buildsContextFromCandidate() {
        var candidate = TestFixtures.httpFlowCandidate();
        when(llm.generateStructured(anyString(), anyMap(), eq(FlowAnalysisOutput.class)))
            .thenReturn(TestFixtures.sampleFlowAnalysisOutput());

        var result = executor.executeStage("stage1", candidate, FlowAnalysisOutput.class);

        assertThat(result.flowName()).isEqualTo(candidate.flowName());
        verify(llm).generateStructured(eq("synthesis-stage1"), argThat(ctx ->
            ctx.containsKey("flowName") &&
            ctx.containsKey("codeEvidence") &&
            ctx.containsKey("logPatterns")
        ), eq(FlowAnalysisOutput.class));
    }

    @Test
    void executeStage_injectsPriorStageOutputs() {
        var candidate = TestFixtures.httpFlowCandidate();
        var priorOutputs = Map.<String, Object>of(
            "flowAnalysis", "{\"flowName\":\"test\"}");
        when(llm.generateStructured(anyString(), anyMap(), eq(CodeExplanationOutput.class)))
            .thenReturn(TestFixtures.sampleCodeExplanationOutput());

        executor.executeStage("stage2", candidate, CodeExplanationOutput.class, priorOutputs);

        verify(llm).generateStructured(eq("synthesis-stage2"), argThat(ctx ->
            ctx.containsKey("flowAnalysis")), eq(CodeExplanationOutput.class));
    }

    @Test
    void generateWithRetry_retriesOnJsonParseFailure() {
        var candidate = TestFixtures.httpFlowCandidate();
        when(llm.generateStructured(anyString(), anyMap(), eq(FlowAnalysisOutput.class)))
            .thenThrow(new RuntimeException("JSON parse error"))
            .thenThrow(new RuntimeException("JSON parse error"))
            .thenReturn(TestFixtures.sampleFlowAnalysisOutput());

        var result = executor.executeStage("stage1", candidate, FlowAnalysisOutput.class);

        assertThat(result).isNotNull();
        verify(llm, times(3)).generateStructured(anyString(), anyMap(), eq(FlowAnalysisOutput.class));
    }

    @Test
    void generateWithRetry_throwsAfterMaxRetries() {
        var candidate = TestFixtures.httpFlowCandidate();
        when(llm.generateStructured(anyString(), anyMap(), eq(FlowAnalysisOutput.class)))
            .thenThrow(new RuntimeException("JSON parse error"));

        assertThatThrownBy(() ->
            executor.executeStage("stage1", candidate, FlowAnalysisOutput.class))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("failed after 3 attempts");
    }
}
```

**`FlowAnalysisStageTest`** — validates stage 1 orchestration and MinIO storage.

```java
@ExtendWith(MockitoExtension.class)
class FlowAnalysisStageTest {

    @Mock SynthesisStageExecutor executor;
    @Mock MinioStorageClient minio;

    @InjectMocks FlowAnalysisStage stage;

    @Test
    void analyze_delegatesToExecutorAndStoresResult() {
        var candidate = TestFixtures.httpFlowCandidate();
        var expected = TestFixtures.sampleFlowAnalysisOutput();
        when(executor.executeStage("stage1", candidate, FlowAnalysisOutput.class))
            .thenReturn(expected);

        var result = stage.analyze(candidate);

        assertThat(result).isEqualTo(expected);
        verify(minio).putJson(eq("evidence"),
            contains("synthesis/stage1/"), eq(expected));
    }
}
```

**`CodeExplanationStageTest`** — validates prior-stage context injection.

```java
@ExtendWith(MockitoExtension.class)
class CodeExplanationStageTest {

    @Mock SynthesisStageExecutor executor;
    @Mock MinioStorageClient minio;

    @InjectMocks CodeExplanationStage stage;

    @Test
    void explain_passesPriorFlowAnalysisAsContext() {
        var candidate = TestFixtures.httpFlowCandidate();
        var flowAnalysis = TestFixtures.sampleFlowAnalysisOutput();
        when(executor.executeStage(eq("stage2"), eq(candidate),
            eq(CodeExplanationOutput.class), anyMap()))
            .thenReturn(TestFixtures.sampleCodeExplanationOutput());

        stage.explain(candidate, flowAnalysis);

        verify(executor).executeStage(eq("stage2"), eq(candidate),
            eq(CodeExplanationOutput.class),
            argThat(map -> map.containsKey("priorStageOutput")));
    }
}
```

**`RiskAssessmentStageTest`** — validates both prior stages are injected.

```java
@ExtendWith(MockitoExtension.class)
class RiskAssessmentStageTest {

    @Mock SynthesisStageExecutor executor;
    @Mock MinioStorageClient minio;

    @InjectMocks RiskAssessmentStage stage;

    @Test
    void assess_injectsBothPriorStageOutputs() {
        var candidate = TestFixtures.httpFlowCandidate();
        var analysis = TestFixtures.sampleFlowAnalysisOutput();
        var explanation = TestFixtures.sampleCodeExplanationOutput();
        when(executor.executeStage(eq("stage3"), eq(candidate),
            eq(RiskAssessmentOutput.class), anyMap()))
            .thenReturn(TestFixtures.sampleRiskAssessmentOutput());

        stage.assess(candidate, analysis, explanation);

        verify(executor).executeStage(eq("stage3"), eq(candidate),
            eq(RiskAssessmentOutput.class),
            argThat(map -> map.containsKey("flowAnalysis")
                        && map.containsKey("codeExplanation")));
    }
}
```

**`SynthesisStages1To3ServiceTest`** — validates sequential execution within a flow and parallel execution across flows.

```java
@ExtendWith(MockitoExtension.class)
class SynthesisStages1To3ServiceTest {

    @Mock FlowAnalysisStage stage1;
    @Mock CodeExplanationStage stage2;
    @Mock RiskAssessmentStage stage3;
    @Mock MeterRegistry meterRegistry;

    @InjectMocks SynthesisStages1To3Service service;

    @BeforeEach
    void setUp() {
        when(meterRegistry.timer(anyString())).thenReturn(new SimpleMeterRegistry().timer("test"));
    }

    @Test
    void runStages1To3_executesSequentially() {
        var candidate = TestFixtures.httpFlowCandidate();
        var analysis = TestFixtures.sampleFlowAnalysisOutput();
        var explanation = TestFixtures.sampleCodeExplanationOutput();
        var risk = TestFixtures.sampleRiskAssessmentOutput();

        when(stage1.analyze(candidate)).thenReturn(analysis);
        when(stage2.explain(candidate, analysis)).thenReturn(explanation);
        when(stage3.assess(candidate, analysis, explanation)).thenReturn(risk);

        var result = service.runStages1To3(candidate);

        assertThat(result.flowAnalysis()).isEqualTo(analysis);
        assertThat(result.codeExplanation()).isEqualTo(explanation);
        assertThat(result.riskAssessment()).isEqualTo(risk);

        var inOrder = inOrder(stage1, stage2, stage3);
        inOrder.verify(stage1).analyze(candidate);
        inOrder.verify(stage2).explain(candidate, analysis);
        inOrder.verify(stage3).assess(candidate, analysis, explanation);
    }

    @Test
    void runAllCandidates_executesFlowsInParallel() {
        var candidates = List.of(
            TestFixtures.httpFlowCandidate(),
            TestFixtures.kafkaFlowCandidate(),
            TestFixtures.grpcFlowCandidate());

        candidates.forEach(c -> {
            when(stage1.analyze(c)).thenReturn(TestFixtures.sampleFlowAnalysisOutput());
            when(stage2.explain(eq(c), any())).thenReturn(TestFixtures.sampleCodeExplanationOutput());
            when(stage3.assess(eq(c), any(), any())).thenReturn(TestFixtures.sampleRiskAssessmentOutput());
        });

        var results = service.runAllCandidates(candidates);

        assertThat(results).hasSize(3);
    }

    @Test
    void runAllCandidates_continuesWhenOneFlowFails() {
        var candidates = List.of(
            TestFixtures.httpFlowCandidate(),
            TestFixtures.kafkaFlowCandidate());

        when(stage1.analyze(candidates.get(0)))
            .thenThrow(new RuntimeException("LLM timeout"));
        when(stage1.analyze(candidates.get(1)))
            .thenReturn(TestFixtures.sampleFlowAnalysisOutput());
        when(stage2.explain(eq(candidates.get(1)), any()))
            .thenReturn(TestFixtures.sampleCodeExplanationOutput());
        when(stage3.assess(eq(candidates.get(1)), any(), any()))
            .thenReturn(TestFixtures.sampleRiskAssessmentOutput());

        var results = service.runAllCandidates(candidates);

        assertThat(results).hasSize(1);
    }
}
```

### Integration Tests

**`SynthesisStageExecutorIntegrationTest`** — end-to-end test with WireMock for the vLLM endpoint and Testcontainers for MinIO.

```java
@SpringBootTest
@Testcontainers
@Tag("integration")
class SynthesisStageExecutorIntegrationTest {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:RELEASE.2024-02-17T01-15-57Z");

    @Container
    @ServiceConnection
    static WireMockContainer wireMock = new WireMockContainer("wiremock/wiremock:3.5.4")
        .withMappingFromResource("llm-stubs", "wiremock/synthesis-stubs.json");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("flowforge.minio.endpoint", minio::getS3URL);
        registry.add("flowforge.llm.base-url",
            () -> wireMock.getBaseUrl() + "/v1");
    }

    @Autowired SynthesisStageExecutor executor;
    @Autowired MinioStorageClient minioClient;

    @Test
    void fullStage1Execution_parsesStructuredJsonFromLlm() {
        var candidate = TestFixtures.httpFlowCandidate();

        var result = executor.executeStage("stage1", candidate, FlowAnalysisOutput.class);

        assertThat(result).isNotNull();
        assertThat(result.flowName()).isNotBlank();
        assertThat(result.interactions()).isNotEmpty();
    }

    @Test
    void retryOnMalformedJson_eventuallySucceeds() {
        // WireMock stub returns malformed JSON on first call, valid on second
        var candidate = TestFixtures.httpFlowCandidate();

        var result = executor.executeStage("stage1", candidate, FlowAnalysisOutput.class);

        assertThat(result).isNotNull();
    }
}
```

**`SynthesisStages1To3IntegrationTest`** — validates the full 3-stage pipeline with MinIO evidence storage.

```java
@SpringBootTest
@Testcontainers
@Tag("integration")
class SynthesisStages1To3IntegrationTest {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:RELEASE.2024-02-17T01-15-57Z");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("flowforge.minio.endpoint", minio::getS3URL);
    }

    @Autowired SynthesisStages1To3Service service;
    @Autowired MinioStorageClient minioClient;

    @Test
    void runStages1To3_storesAllIntermediateResultsInMinio() {
        var candidate = TestFixtures.httpFlowCandidate();

        var result = service.runStages1To3(candidate);

        // Verify all 3 stage outputs in MinIO
        assertThat(minioClient.exists("evidence",
            "synthesis/stage1/%s/%s.json".formatted(
                candidate.snapshotId(), candidate.candidateId()))).isTrue();
        assertThat(minioClient.exists("evidence",
            "synthesis/stage2/%s/%s.json".formatted(
                candidate.snapshotId(), candidate.candidateId()))).isTrue();
        assertThat(minioClient.exists("evidence",
            "synthesis/stage3/%s/%s.json".formatted(
                candidate.snapshotId(), candidate.candidateId()))).isTrue();
    }
}
```

### Test Fixtures & Sample Data

Create a shared test fixture class at `libs/synthesis/src/test/java/com/flowforge/synthesis/TestFixtures.java`:

- **`httpFlowCandidate()`** — a 3-service HTTP flow (Gateway → OrderService → InventoryService) with code evidence snippets and log patterns
- **`kafkaFlowCandidate()`** — an event-driven flow with Kafka producer/consumer pairs
- **`grpcFlowCandidate()`** — a gRPC flow for testing protocol diversity
- **`sampleFlowAnalysisOutput()`** — pre-built `FlowAnalysisOutput` with 3 interaction steps and a data flow description
- **`sampleCodeExplanationOutput()`** — pre-built `CodeExplanationOutput` with 2 code artifacts and 1 reactive pattern
- **`sampleRiskAssessmentOutput()`** — pre-built `RiskAssessmentOutput` with HIGH overall risk, 2 risks, and 1 coupling point

Create WireMock stub files under `libs/synthesis/src/test/resources/wiremock/`:

- **`synthesis-stubs.json`** — WireMock mapping that returns valid structured JSON for each synthesis stage prompt pattern
- **`malformed-then-valid.json`** — scenario-based stub that returns malformed JSON on first call, valid JSON on retry

### Mocking Strategy

| Dependency | Mock or Real | Rationale |
|---|---|---|
| `LlmGenerationService` | **Mock** (unit) / **WireMock** (integration) | LLM calls are expensive and non-deterministic; WireMock provides controlled responses |
| `HybridRetrievalService` | **Mock** | Retrieval is tested in its own module (Stage 18) |
| `MinioStorageClient` | **Mock** (unit) / **Testcontainers** (integration) | Unit tests verify calls; integration tests verify actual object storage |
| `MeterRegistry` | **SimpleMeterRegistry** | Lightweight in-memory registry for verifying timer/counter interactions |
| `FlowCandidate` | **Test fixture records** | Deterministic flow candidates with known evidence data |

### CI/CD Considerations

- Tag unit tests with `@Tag("unit")` (default), integration tests with `@Tag("integration")`
- Gradle test filtering: `./gradlew :libs:synthesis:test` for unit tests, `./gradlew :libs:synthesis:integrationTest -PincludeTags=integration` for integration
- Integration tests require Docker (MinIO + WireMock containers) — ensure CI runners have Docker-in-Docker or a Docker socket
- WireMock stubs should be version-controlled alongside test code in `src/test/resources/wiremock/`
- Set `-Djunit.jupiter.execution.parallel.enabled=true` for parallel unit test execution across stage tests
- Virtual thread tests (`runAllCandidates`) require JDK 21+ on CI runners

## Verification

**Stage 21 sign-off requires all stages 1 through 21 to pass.** Run: `make verify`.

The verification report for stage 21 is `logs/stage-21.log`. It contains **cumulative output for stages 1–21** (Stage 1, then Stage 2, … then Stage 21 output).

| Check | How to verify | Pass criteria |
|---|---|---|
| Stage 1 output | Analyze simple HTTP flow | FlowAnalysisOutput with purpose + interactions |
| Stage 1 interactions | 3-service flow | 3 InteractionStep objects |
| Stage 1 data flow | Flow with clear input/output | DataFlowDescription populated |
| Stage 2 output | Explain code for flow | CodeExplanationOutput with artifacts |
| Stage 2 reactive | Flow with reactive code | ReactivePatternExplanation present |
| Stage 2 annotations | Micronaut endpoint | Annotations explained |
| Stage 3 risks | Complex reactive flow | MigrationRisk list with severities |
| Stage 3 coupling | Two tightly coupled services | CouplingPoint identified |
| Stage 3 recommendations | Any flow | Non-empty recommendations |
| Sequential execution | Stages 1→2→3 | Each uses previous output |
| JSON output | BeanOutputConverter | Valid JSON parsed to record |
| MinIO evidence | Run all 3 stages | 3 JSON files per flow in evidence |
| Metrics | Run pipeline | stage1/2/3 latency timers populated |

## Files to create

- `libs/synthesis/build.gradle.kts`
- `libs/synthesis/src/main/java/com/flowforge/synthesis/model/FlowAnalysisOutput.java`
- `libs/synthesis/src/main/java/com/flowforge/synthesis/model/CodeExplanationOutput.java`
- `libs/synthesis/src/main/java/com/flowforge/synthesis/model/RiskAssessmentOutput.java`
- `libs/synthesis/src/main/java/com/flowforge/synthesis/executor/SynthesisStageExecutor.java`
- `libs/synthesis/src/main/java/com/flowforge/synthesis/stage/FlowAnalysisStage.java`
- `libs/synthesis/src/main/java/com/flowforge/synthesis/stage/CodeExplanationStage.java`
- `libs/synthesis/src/main/java/com/flowforge/synthesis/stage/RiskAssessmentStage.java`
- `libs/synthesis/src/main/java/com/flowforge/synthesis/service/SynthesisStages1To3Service.java`
- `libs/synthesis/src/test/java/.../SynthesisStageExecutorTest.java` (WireMock vLLM)
- `libs/synthesis/src/test/java/.../SynthesisStages1To3ServiceTest.java`

## Depends on

- Stage 19 (flow candidates)
- Stage 20 (LLM generation)

## Produces

- Per-flow structured outputs: FlowAnalysis, CodeExplanation, RiskAssessment
- Intermediate synthesis results in MinIO evidence bucket
- Foundation for stages 4-6 (dependency mapping, migration plan, final narrative)
