# Stage 22 — Synthesis Stages 4–6 (Dependencies, Migration Plan, Final Narrative)

## Goal

Implement the final three LLM synthesis stages:
4. **Dependency Mapping** — Map all runtime and build dependencies, identify version conflicts
5. **Migration Plan** — Generate step-by-step migration recommendations per flow
6. **Final Narrative** — Produce the consolidated prose section for `system-flows-research.md`

## Prerequisites

- Stage 21 (synthesis stages 1-3 outputs)

## What to build

### 22.1 Stage 4-6 output records

```java
// Stage 4: Dependency Mapping
public record DependencyMappingOutput(
    String flowName,
    List<RuntimeDependency> runtimeDependencies,
    List<BuildDependency> buildDependencies,
    List<DependencyConflict> conflicts,
    List<SharedLibrary> sharedLibraries,
    DependencyGraph dependencyGraph
) {}

public record RuntimeDependency(
    String serviceName,
    String dependencyName,
    String version,
    String purpose,
    DependencyType type
) {
    public enum DependencyType { DATABASE, CACHE, MESSAGE_BROKER, EXTERNAL_API, CONFIG_SERVER, SERVICE_MESH }
}

public record BuildDependency(
    String serviceName,
    String groupId,
    String artifactId,
    String version,
    String scope
) {}

public record DependencyConflict(
    String artifactId,
    String service1,
    String version1,
    String service2,
    String version2,
    String resolution
) {}

public record SharedLibrary(
    String name,
    String version,
    List<String> consumers,
    String migrationImpact
) {}

public record DependencyGraph(
    List<String> nodes,
    List<DependencyEdge> edges
) {}
public record DependencyEdge(String from, String to, String label) {}

// Stage 5: Migration Plan
public record MigrationPlanOutput(
    String flowName,
    MigrationStrategy strategy,
    List<MigrationPhase> phases,
    List<String> prerequisites,
    EstimatedEffort effort,
    List<String> testingStrategy,
    RollbackPlan rollbackPlan
) {
    public enum MigrationStrategy { BIG_BANG, STRANGLER_FIG, PARALLEL_RUN, BRANCH_BY_ABSTRACTION }
}

public record MigrationPhase(
    int order,
    String phaseName,
    String description,
    List<String> services,
    List<String> tasks,
    List<String> deliverables,
    String estimatedDuration,
    List<String> risks
) {}

public record EstimatedEffort(
    String totalDuration,
    int teamSize,
    String complexityRating,
    Map<String, String> perServiceEstimate
) {}

public record RollbackPlan(
    String strategy,
    List<String> rollbackSteps,
    String dataBackupApproach,
    String featureFlagUsage
) {}

// Stage 6: Final Narrative
public record FinalNarrativeOutput(
    String flowName,
    String executiveSummary,
    String detailedNarrative,
    List<DiagramSpec> diagrams,
    List<KeyFinding> keyFindings,
    List<String> openQuestions,
    String recommendedNextSteps
) {}

public record DiagramSpec(
    String title,
    DiagramType type,
    String mermaidCode
) {
    public enum DiagramType { SEQUENCE, FLOWCHART, C4_CONTAINER, STATE_MACHINE }
}

public record KeyFinding(
    String title,
    String description,
    FindingSeverity severity,
    String evidence
) {
    public enum FindingSeverity { INFO, WARNING, CRITICAL }
}
```

### 22.2 Stage 4: Dependency mapping

```java
@Component
public class DependencyMappingStage {

    private final SynthesisStageExecutor executor;
    private final MinioStorageClient minio;

    public DependencyMappingOutput mapDependencies(
            FlowCandidate candidate,
            SynthesisPartialResult stages1to3) {
        var output = executor.executeStage("stage4", candidate,
            DependencyMappingOutput.class,
            Map.of(
                "priorStageOutput", serializeToString(stages1to3),
                "buildEvidence", formatBuildEvidence(candidate)
            ));

        minio.putJson("evidence",
            "synthesis/stage4/%s/%s.json".formatted(
                candidate.snapshotId(), candidate.candidateId()),
            output);

        return output;
    }

    private String formatBuildEvidence(FlowCandidate candidate) {
        return candidate.evidence().codeSnippets().stream()
            .collect(Collectors.joining("\n---\n"));
    }
}
```

### 22.3 Stage 5: Migration plan

```java
@Component
public class MigrationPlanStage {

    private final SynthesisStageExecutor executor;
    private final MinioStorageClient minio;

    public MigrationPlanOutput planMigration(
            FlowCandidate candidate,
            SynthesisPartialResult stages1to3,
            DependencyMappingOutput dependencyMapping) {
        var output = executor.executeStage("stage5", candidate,
            MigrationPlanOutput.class,
            Map.of("riskAssessment", serializeToString(stages1to3.riskAssessment()),
                   "dependencyMapping", serializeToString(dependencyMapping)));

        minio.putJson("evidence",
            "synthesis/stage5/%s/%s.json".formatted(
                candidate.snapshotId(), candidate.candidateId()),
            output);

        return output;
    }
}
```

### 22.4 Stage 6: Final narrative

```java
@Component
public class FinalNarrativeStage {

    private final SynthesisStageExecutor executor;
    private final MinioStorageClient minio;

    /**
     * Generate the final narrative section for the research document.
     * This stage receives ALL prior stage outputs as context.
     */
    public FinalNarrativeOutput generateNarrative(
            FlowCandidate candidate,
            SynthesisPartialResult stages1to3,
            DependencyMappingOutput dependencyMapping,
            MigrationPlanOutput migrationPlan) {
        var output = executor.executeStage("stage6", candidate,
            FinalNarrativeOutput.class,
            Map.of("flowAnalysis", serializeToString(stages1to3.flowAnalysis()),
                   "codeExplanation", serializeToString(stages1to3.codeExplanation()),
                   "riskAssessment", serializeToString(stages1to3.riskAssessment()),
                   "dependencyMapping", serializeToString(dependencyMapping),
                   "migrationPlan", serializeToString(migrationPlan)));

        minio.putJson("evidence",
            "synthesis/stage6/%s/%s.json".formatted(
                candidate.snapshotId(), candidate.candidateId()),
            output);

        return output;
    }
}
```

### 22.5 Full synthesis orchestrator (stages 4-6)

```java
@Service
public class SynthesisStages4To6Service {

    private final DependencyMappingStage stage4;
    private final MigrationPlanStage stage5;
    private final FinalNarrativeStage stage6;
    private final MeterRegistry meterRegistry;

    /**
     * Complete the synthesis pipeline for a single flow.
     * Receives stages 1-3 results as input.
     */
    public SynthesisFullResult runStages4To6(FlowCandidate candidate,
                                              SynthesisPartialResult stages1to3) {
        var depMapping = meterRegistry.timer("flowforge.synthesis.stage4.latency")
            .record(() -> stage4.mapDependencies(candidate, stages1to3));

        var migrationPlan = meterRegistry.timer("flowforge.synthesis.stage5.latency")
            .record(() -> stage5.planMigration(candidate, stages1to3, depMapping));

        var narrative = meterRegistry.timer("flowforge.synthesis.stage6.latency")
            .record(() -> stage6.generateNarrative(candidate, stages1to3, depMapping, migrationPlan));

        return new SynthesisFullResult(
            candidate.candidateId(),
            stages1to3,
            depMapping,
            migrationPlan,
            narrative
        );
    }
}

public record SynthesisFullResult(
    UUID candidateId,
    SynthesisPartialResult stages1to3,
    DependencyMappingOutput dependencyMapping,
    MigrationPlanOutput migrationPlan,
    FinalNarrativeOutput narrative
) {}
```

### 22.6 Complete synthesis pipeline

```java
@Service
public class SynthesisPipeline {

    private final SynthesisStages1To3Service stages1to3;
    private final SynthesisStages4To6Service stages4to6;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    /**
     * Run the full 6-stage synthesis pipeline for all flow candidates.
     */
    public List<SynthesisFullResult> synthesize(UUID snapshotId, List<FlowCandidate> candidates) {
        log.info("Starting synthesis pipeline for {} candidates", candidates.size());

        var results = new ArrayList<SynthesisFullResult>();

        for (var candidate : candidates) {
            try {
                // Stages 1-3 (sequential within flow)
                var partial = stages1to3.runStages1To3(candidate);

                // Stages 4-6 (sequential within flow, uses 1-3 output)
                var full = stages4to6.runStages4To6(candidate, partial);

                results.add(full);
                meterRegistry.counter("flowforge.synthesis.flows.completed").increment();

            } catch (Exception e) {
                log.error("Synthesis failed for flow {}: {}",
                    candidate.flowName(), e.getMessage(), e);
                meterRegistry.counter("flowforge.synthesis.flows.failed").increment();
                // Store partial results so they can be resumed later
                minio.putJson("evidence",
                    "synthesis/partial/%s/%s.json".formatted(snapshotId, candidate.candidateId()),
                    Map.of("flowName", candidate.flowName(),
                           "error", e.getMessage(),
                           "failedAt", Instant.now().toString()));
            }
        }

        // Store complete results
        minio.putJson("evidence", "synthesis/complete/" + snapshotId + ".json", results);

        return results;
    }
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

**`DependencyMappingStageTest`** — validates dependency mapping with prior-stage context injection.

```java
@ExtendWith(MockitoExtension.class)
class DependencyMappingStageTest {

    @Mock SynthesisStageExecutor executor;
    @Mock MinioStorageClient minio;

    @InjectMocks DependencyMappingStage stage;

    @Test
    void mapDependencies_passesStages1To3AsContext() {
        var candidate = TestFixtures.httpFlowCandidate();
        var partial = TestFixtures.samplePartialResult();
        var expected = TestFixtures.sampleDependencyMappingOutput();
        when(executor.executeStage(eq("stage4"), eq(candidate),
            eq(DependencyMappingOutput.class), anyMap()))
            .thenReturn(expected);

        var result = stage.mapDependencies(candidate, partial);

        assertThat(result.runtimeDependencies()).isNotEmpty();
        verify(minio).putJson(eq("evidence"),
            contains("synthesis/stage4/"), eq(expected));
    }

    @Test
    void mapDependencies_detectsVersionConflicts() {
        var candidate = TestFixtures.multiVersionFlowCandidate();
        var partial = TestFixtures.samplePartialResult();
        var output = TestFixtures.dependencyOutputWithConflicts();
        when(executor.executeStage(eq("stage4"), eq(candidate),
            eq(DependencyMappingOutput.class), anyMap()))
            .thenReturn(output);

        var result = stage.mapDependencies(candidate, partial);

        assertThat(result.conflicts()).isNotEmpty();
        assertThat(result.conflicts().get(0).resolution()).isNotBlank();
    }
}
```

**`MigrationPlanStageTest`** — validates migration plan uses risk assessment and dependency mapping.

```java
@ExtendWith(MockitoExtension.class)
class MigrationPlanStageTest {

    @Mock SynthesisStageExecutor executor;
    @Mock MinioStorageClient minio;

    @InjectMocks MigrationPlanStage stage;

    @Test
    void planMigration_injectsRiskAndDependencyContext() {
        var candidate = TestFixtures.httpFlowCandidate();
        var partial = TestFixtures.samplePartialResult();
        var depMapping = TestFixtures.sampleDependencyMappingOutput();
        when(executor.executeStage(eq("stage5"), eq(candidate),
            eq(MigrationPlanOutput.class), anyMap()))
            .thenReturn(TestFixtures.sampleMigrationPlanOutput());

        stage.planMigration(candidate, partial, depMapping);

        verify(executor).executeStage(eq("stage5"), eq(candidate),
            eq(MigrationPlanOutput.class),
            argThat(map -> map.containsKey("riskAssessment")
                        && map.containsKey("dependencyMapping")));
    }

    @Test
    void planMigration_returnsOrderedPhases() {
        var candidate = TestFixtures.httpFlowCandidate();
        var plan = TestFixtures.sampleMigrationPlanOutput();
        when(executor.executeStage(anyString(), any(), eq(MigrationPlanOutput.class), anyMap()))
            .thenReturn(plan);

        var result = stage.planMigration(candidate,
            TestFixtures.samplePartialResult(),
            TestFixtures.sampleDependencyMappingOutput());

        assertThat(result.phases())
            .extracting(MigrationPhase::order)
            .isSorted();
    }
}
```

**`FinalNarrativeStageTest`** — validates all 5 prior stage outputs are injected.

```java
@ExtendWith(MockitoExtension.class)
class FinalNarrativeStageTest {

    @Mock SynthesisStageExecutor executor;
    @Mock MinioStorageClient minio;

    @InjectMocks FinalNarrativeStage stage;

    @Test
    void generateNarrative_injectsAllPriorStageOutputs() {
        var candidate = TestFixtures.httpFlowCandidate();
        var partial = TestFixtures.samplePartialResult();
        var depMapping = TestFixtures.sampleDependencyMappingOutput();
        var migrationPlan = TestFixtures.sampleMigrationPlanOutput();
        when(executor.executeStage(eq("stage6"), eq(candidate),
            eq(FinalNarrativeOutput.class), anyMap()))
            .thenReturn(TestFixtures.sampleFinalNarrativeOutput());

        stage.generateNarrative(candidate, partial, depMapping, migrationPlan);

        verify(executor).executeStage(eq("stage6"), eq(candidate),
            eq(FinalNarrativeOutput.class),
            argThat(map -> map.containsKey("flowAnalysis")
                        && map.containsKey("codeExplanation")
                        && map.containsKey("riskAssessment")
                        && map.containsKey("dependencyMapping")
                        && map.containsKey("migrationPlan")));
    }

    @Test
    void generateNarrative_includesMermaidDiagrams() {
        var candidate = TestFixtures.httpFlowCandidate();
        var narrative = TestFixtures.sampleFinalNarrativeOutput();
        when(executor.executeStage(anyString(), any(), eq(FinalNarrativeOutput.class), anyMap()))
            .thenReturn(narrative);

        var result = stage.generateNarrative(candidate,
            TestFixtures.samplePartialResult(),
            TestFixtures.sampleDependencyMappingOutput(),
            TestFixtures.sampleMigrationPlanOutput());

        assertThat(result.diagrams()).isNotEmpty();
        assertThat(result.diagrams().get(0).mermaidCode()).contains("sequenceDiagram");
    }
}
```

**`SynthesisStages4To6ServiceTest`** — validates sequential stage chaining.

```java
@ExtendWith(MockitoExtension.class)
class SynthesisStages4To6ServiceTest {

    @Mock DependencyMappingStage stage4;
    @Mock MigrationPlanStage stage5;
    @Mock FinalNarrativeStage stage6;
    @Mock MeterRegistry meterRegistry;

    @InjectMocks SynthesisStages4To6Service service;

    @BeforeEach
    void setUp() {
        when(meterRegistry.timer(anyString())).thenReturn(new SimpleMeterRegistry().timer("test"));
    }

    @Test
    void runStages4To6_chainsOutputsSequentially() {
        var candidate = TestFixtures.httpFlowCandidate();
        var partial = TestFixtures.samplePartialResult();
        var depMapping = TestFixtures.sampleDependencyMappingOutput();
        var migPlan = TestFixtures.sampleMigrationPlanOutput();
        var narrative = TestFixtures.sampleFinalNarrativeOutput();

        when(stage4.mapDependencies(candidate, partial)).thenReturn(depMapping);
        when(stage5.planMigration(candidate, partial, depMapping)).thenReturn(migPlan);
        when(stage6.generateNarrative(candidate, partial, depMapping, migPlan)).thenReturn(narrative);

        var result = service.runStages4To6(candidate, partial);

        assertThat(result.dependencyMapping()).isEqualTo(depMapping);
        assertThat(result.migrationPlan()).isEqualTo(migPlan);
        assertThat(result.narrative()).isEqualTo(narrative);

        var inOrder = inOrder(stage4, stage5, stage6);
        inOrder.verify(stage4).mapDependencies(candidate, partial);
        inOrder.verify(stage5).planMigration(candidate, partial, depMapping);
        inOrder.verify(stage6).generateNarrative(candidate, partial, depMapping, migPlan);
    }
}
```

**`SynthesisPipelineTest`** — validates full 6-stage pipeline, partial failure checkpointing, and error resilience.

```java
@ExtendWith(MockitoExtension.class)
class SynthesisPipelineTest {

    @Mock SynthesisStages1To3Service stages1to3;
    @Mock SynthesisStages4To6Service stages4to6;
    @Mock MinioStorageClient minio;
    @Mock MeterRegistry meterRegistry;

    @InjectMocks SynthesisPipeline pipeline;

    @BeforeEach
    void setUp() {
        when(meterRegistry.counter(anyString())).thenReturn(new SimpleMeterRegistry().counter("test"));
    }

    @Test
    void synthesize_runsFullPipelineForAllCandidates() {
        var candidates = List.of(
            TestFixtures.httpFlowCandidate(),
            TestFixtures.kafkaFlowCandidate());
        var partial = TestFixtures.samplePartialResult();
        var full = TestFixtures.sampleFullResult();

        when(stages1to3.runStages1To3(any())).thenReturn(partial);
        when(stages4to6.runStages4To6(any(), eq(partial))).thenReturn(full);

        var results = pipeline.synthesize(UUID.randomUUID(), candidates);

        assertThat(results).hasSize(2);
        verify(minio).putJson(eq("evidence"), contains("synthesis/complete/"), eq(results));
    }

    @Test
    void synthesize_checkpointsPartialFailureToMinio() {
        var snapshotId = UUID.randomUUID();
        var candidates = List.of(TestFixtures.httpFlowCandidate());

        when(stages1to3.runStages1To3(any()))
            .thenThrow(new RuntimeException("LLM timeout on stage 2"));

        var results = pipeline.synthesize(snapshotId, candidates);

        assertThat(results).isEmpty();
        verify(minio).putJson(eq("evidence"),
            contains("synthesis/partial/" + snapshotId),
            argThat(map -> ((Map<?, ?>) map).containsKey("error")));
    }

    @Test
    void synthesize_continuesAfterOneFlowFails() {
        var candidates = List.of(
            TestFixtures.httpFlowCandidate(),
            TestFixtures.kafkaFlowCandidate());

        when(stages1to3.runStages1To3(candidates.get(0)))
            .thenThrow(new RuntimeException("Stage 1 failure"));
        when(stages1to3.runStages1To3(candidates.get(1)))
            .thenReturn(TestFixtures.samplePartialResult());
        when(stages4to6.runStages4To6(any(), any()))
            .thenReturn(TestFixtures.sampleFullResult());

        var results = pipeline.synthesize(UUID.randomUUID(), candidates);

        assertThat(results).hasSize(1);
    }
}
```

### Integration Tests

**`SynthesisPipelineIntegrationTest`** — full pipeline with WireMock LLM and Testcontainers MinIO.

```java
@SpringBootTest
@Testcontainers
@Tag("integration")
class SynthesisPipelineIntegrationTest {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:RELEASE.2024-02-17T01-15-57Z");

    @Container
    static WireMockContainer wireMock = new WireMockContainer("wiremock/wiremock:3.5.4")
        .withMappingFromResource("llm-stubs", "wiremock/pipeline-stubs.json");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("flowforge.minio.endpoint", minio::getS3URL);
        registry.add("flowforge.llm.base-url",
            () -> wireMock.getBaseUrl() + "/v1");
    }

    @Autowired SynthesisPipeline pipeline;
    @Autowired MinioStorageClient minioClient;

    @Test
    void synthesize_stores6StageOutputsPerFlowAndCompleteResult() {
        var snapshotId = UUID.randomUUID();
        var candidates = List.of(TestFixtures.httpFlowCandidate());

        var results = pipeline.synthesize(snapshotId, candidates);

        assertThat(results).hasSize(1);
        for (int stage = 1; stage <= 6; stage++) {
            assertThat(minioClient.exists("evidence",
                "synthesis/stage%d/%s/%s.json".formatted(stage,
                    candidates.get(0).snapshotId(),
                    candidates.get(0).candidateId()))).isTrue();
        }
        assertThat(minioClient.exists("evidence",
            "synthesis/complete/" + snapshotId + ".json")).isTrue();
    }

    @Test
    void synthesize_partialFailurePersistsCheckpoint() {
        // Configure WireMock to fail on stage 5 requests
        var snapshotId = UUID.randomUUID();
        var candidates = List.of(TestFixtures.httpFlowCandidate());

        // Pipeline should checkpoint partial failure
        var results = pipeline.synthesize(snapshotId, candidates);

        assertThat(minioClient.exists("evidence",
            "synthesis/partial/" + snapshotId + "/" +
                candidates.get(0).candidateId() + ".json")).isTrue();
    }
}
```

### Test Fixtures & Sample Data

Extend the shared `TestFixtures` class from Stage 21 with additional factory methods in `libs/synthesis/src/test/java/com/flowforge/synthesis/TestFixtures.java`:

- **`samplePartialResult()`** — a `SynthesisPartialResult` combining the stage 1-3 fixture outputs
- **`sampleDependencyMappingOutput()`** — `DependencyMappingOutput` with 2 runtime deps (PostgreSQL, Redis), 3 build deps, and 1 version conflict
- **`dependencyOutputWithConflicts()`** — variant with multiple `DependencyConflict` entries for conflict detection tests
- **`sampleMigrationPlanOutput()`** — `MigrationPlanOutput` with STRANGLER_FIG strategy, 3 ordered phases, and a rollback plan
- **`sampleFinalNarrativeOutput()`** — `FinalNarrativeOutput` with prose narrative, 1 Mermaid sequence diagram, and 2 key findings
- **`sampleFullResult()`** — a `SynthesisFullResult` combining all 6 stage outputs
- **`multiVersionFlowCandidate()`** — flow candidate where services use conflicting library versions

WireMock stubs under `libs/synthesis/src/test/resources/wiremock/`:

- **`pipeline-stubs.json`** — mappings for all 6 stages returning valid structured JSON, keyed by prompt pattern matching (`synthesis-stage4`, `synthesis-stage5`, `synthesis-stage6`)
- **`stage5-failure.json`** — scenario stub that returns a 500 error for stage 5, testing partial failure checkpointing

### Mocking Strategy

| Dependency | Mock or Real | Rationale |
|---|---|---|
| `SynthesisStageExecutor` | **Mock** (stage tests) / **Real** (pipeline integration) | Each stage test isolates its own logic; pipeline tests validate end-to-end chaining |
| `SynthesisStages1To3Service` | **Mock** (pipeline unit test) / **Real** (pipeline integration) | Unit tests verify pipeline orchestration; integration tests run all stages |
| `LlmGenerationService` | **WireMock** (integration) | Controlled LLM responses for deterministic pipeline testing |
| `MinioStorageClient` | **Mock** (unit) / **Testcontainers** (integration) | Unit tests verify storage calls; integration tests verify actual MinIO persistence |
| `MeterRegistry` | **SimpleMeterRegistry** | In-memory registry for verifying counter/timer increments |

### CI/CD Considerations

- Tag unit tests with `@Tag("unit")`, integration tests with `@Tag("integration")`
- Gradle: `./gradlew :libs:synthesis:test` (unit), `./gradlew :libs:synthesis:integrationTest -PincludeTags=integration` (integration)
- Integration tests require Docker for MinIO + WireMock containers
- Pipeline integration tests are heavyweight (6 WireMock round-trips per flow) — run separately in a `synthesis-integration` CI stage
- WireMock stubs should cover both success and failure scenarios for each stage to validate checkpoint persistence
- Set `TESTCONTAINERS_REUSE_ENABLE=true` for faster local iteration with persistent containers

## Verification

**Stage 22 sign-off requires all stages 1 through 22 to pass.** Run: `make verify`.

The verification report for stage 22 is `logs/stage-22.log`. It contains **cumulative output for stages 1–22** (Stage 1, then Stage 2, … then Stage 22 output).

| Check | How to verify | Pass criteria |
|---|---|---|
| Stage 4 deps | Map 3-service flow | Runtime + build deps listed |
| Stage 4 conflicts | Version mismatch | DependencyConflict with resolution |
| Stage 4 shared libs | Common internal lib | SharedLibrary with consumers |
| Stage 5 strategy | Complex flow | MigrationStrategy selected |
| Stage 5 phases | Any flow | Ordered MigrationPhases with tasks |
| Stage 5 effort | Any flow | EstimatedEffort with duration |
| Stage 5 rollback | Any flow | RollbackPlan with steps |
| Stage 6 narrative | Complete pipeline | Prose narrative generated |
| Stage 6 diagrams | Flow with 3+ services | Mermaid sequence diagram |
| Stage 6 findings | Complex flow | KeyFindings with severities |
| Pipeline | 3 flow candidates | 3 SynthesisFullResult objects |
| Evidence | Full run | 6 stage JSONs per flow in MinIO |
| Error handling | LLM failure mid-pipeline | Error logged, flow skipped, others continue |
| Metrics | Full run | Per-stage latency timers populated |

## Files to create

- `libs/synthesis/src/main/java/com/flowforge/synthesis/model/DependencyMappingOutput.java`
- `libs/synthesis/src/main/java/com/flowforge/synthesis/model/MigrationPlanOutput.java`
- `libs/synthesis/src/main/java/com/flowforge/synthesis/model/FinalNarrativeOutput.java`
- `libs/synthesis/src/main/java/com/flowforge/synthesis/stage/DependencyMappingStage.java`
- `libs/synthesis/src/main/java/com/flowforge/synthesis/stage/MigrationPlanStage.java`
- `libs/synthesis/src/main/java/com/flowforge/synthesis/stage/FinalNarrativeStage.java`
- `libs/synthesis/src/main/java/com/flowforge/synthesis/service/SynthesisStages4To6Service.java`
- `libs/synthesis/src/main/java/com/flowforge/synthesis/pipeline/SynthesisPipeline.java`
- `libs/llm/src/main/resources/prompts/synthesis-stage4.st`
- `libs/llm/src/main/resources/prompts/synthesis-stage5.st`
- `libs/llm/src/main/resources/prompts/synthesis-stage6.st`
- `libs/synthesis/src/test/java/.../SynthesisPipelineTest.java`

## Depends on

- Stage 21 (stages 1-3 output)
- Stage 20 (LLM generation)

## Produces

- Per-flow: dependency map, migration plan, final narrative
- Complete synthesis results for all flows
- Mermaid diagram specifications for each flow
- All intermediate results in MinIO evidence bucket
