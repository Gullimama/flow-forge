# Stage 19 — Flow Candidate Builder

## Goal

Combine graph topology, code parse results, log patterns, anomaly episodes, and sequential mining patterns to build **flow candidates** — structured representations of potential system flows that will be sent to the LLM for synthesis. Each flow candidate aggregates all evidence relevant to a particular inter-service interaction pattern.

## Prerequisites

- Stage 11 (Neo4j knowledge graph)
- Stage 12 (anomaly episodes)
- Stage 13 (sequential patterns)
- Stage 18 (hybrid retrieval)

## What to build

### 19.1 Flow candidate model

```java
public record FlowCandidate(
    UUID candidateId,
    UUID snapshotId,
    String flowName,                       // e.g., "booking-creation-flow"
    FlowType flowType,
    List<FlowStep> steps,
    List<String> involvedServices,
    FlowEvidence evidence,
    double confidenceScore,
    FlowComplexity complexity
) {
    public enum FlowType {
        SYNC_REQUEST,           // HTTP request-response chain
        ASYNC_EVENT,            // Kafka event-driven
        MIXED,                  // Combination of sync + async
        BATCH_PROCESS,          // Scheduled / batch
        ERROR_HANDLING          // Error propagation path
    }

    public enum FlowComplexity { LOW, MEDIUM, HIGH, VERY_HIGH }
}

public record FlowStep(
    int order,
    String serviceName,
    String action,                     // e.g., "POST /api/bookings"
    StepType stepType,
    Optional<String> classFqn,
    Optional<String> methodName,
    Optional<String> kafkaTopic,
    List<String> annotations,
    ReactiveComplexity reactiveComplexity,
    Optional<String> errorHandling
) {
    public enum StepType {
        HTTP_ENDPOINT, HTTP_CLIENT_CALL, KAFKA_PRODUCE,
        KAFKA_CONSUME, DATABASE_QUERY, CACHE_LOOKUP, EXTERNAL_CALL
    }
}

public record FlowEvidence(
    List<String> codeSnippets,         // Relevant code from retrieval
    List<String> logPatterns,          // Relevant log templates
    List<String> graphPaths,           // Neo4j path descriptions
    List<EnrichedPattern> sequencePatterns,
    List<AnomalyEpisodeBuilder.AnomalyEpisode> relatedAnomalies,
    Map<String, Object> topologyContext
) {
    public static FlowEvidence empty() {
        return new FlowEvidence(List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
    }
}
```

### 19.2 Flow candidate builder service

```java
@Service
public class FlowCandidateBuilder {

    private final Neo4jGraphQueryService graphQuery;
    private final HybridRetrievalService retrieval;
    private final PatternMiningService patternMining;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    /**
     * Build all flow candidates for a snapshot.
     */
    public List<FlowCandidate> buildCandidates(UUID snapshotId) {
        var candidates = new ArrayList<FlowCandidate>();

        // 1. Build sync request flows from HTTP call chains
        candidates.addAll(buildHttpFlows(snapshotId));

        // 2. Build async event flows from Kafka topology
        candidates.addAll(buildKafkaFlows(snapshotId));

        // 3. Build error handling flows from anomaly episodes
        candidates.addAll(buildErrorFlows(snapshotId));

        // 4. Build flows from discovered sequential patterns
        candidates.addAll(buildPatternFlows(snapshotId));

        // 5. Deduplicate and merge overlapping flows
        var merged = mergeOverlappingFlows(candidates);

        // 6. Enrich each candidate with retrieval evidence
        var enriched = merged.stream()
            .map(c -> enrichWithEvidence(c, snapshotId))
            .toList();

        // 7. Score confidence
        var scored = enriched.stream()
            .map(this::scoreConfidence)
            .sorted(Comparator.comparingDouble(FlowCandidate::confidenceScore).reversed())
            .toList();

        // 8. Store as evidence
        minio.putJson("evidence", "flow-candidates/" + snapshotId + ".json", scored);

        meterRegistry.counter("flowforge.flow.candidates.built").increment(scored.size());

        return scored;
    }

    /**
     * Build flows from HTTP call graph in Neo4j.
     */
    private List<FlowCandidate> buildHttpFlows(UUID snapshotId) {
        // Find all HTTP call chains starting from ingress/gateway services
        var entryPoints = graphQuery.findEntryPointServices();
        var flows = new ArrayList<FlowCandidate>();

        for (var entry : entryPoints) {
            var chains = graphQuery.getCallChainsFrom(entry, 5);  // max depth 5
            for (var chain : chains) {
                var steps = chain.stream()
                    .map(this::chainNodeToFlowStep)
                    .toList();

                if (steps.size() >= 2) {
                    flows.add(new FlowCandidate(
                        UUID.randomUUID(), snapshotId,
                        generateFlowName(steps),
                        FlowCandidate.FlowType.SYNC_REQUEST,
                        steps,
                        steps.stream().map(FlowStep::serviceName).distinct().toList(),
                        FlowEvidence.empty(),
                        0.0,
                        assessComplexity(steps)
                    ));
                }
            }
        }
        return flows;
    }

    /**
     * Build flows from Kafka topic connections.
     */
    private List<FlowCandidate> buildKafkaFlows(UUID snapshotId) {
        var kafkaTopics = graphQuery.findKafkaTopicsWithConnections();
        var flows = new ArrayList<FlowCandidate>();

        for (var topic : kafkaTopics) {
            var producers = topic.get("producers");
            var consumers = topic.get("consumers");
            var topicName = (String) topic.get("name");

            // Build flow: producer → topic → consumer(s)
            // ... (similar structure to HTTP flows)
        }
        return flows;
    }

    /**
     * Enrich a flow candidate with code and log evidence using a single
     * batched retrieval query per flow (not per step) to avoid N+1.
     */
    private FlowCandidate enrichWithEvidence(FlowCandidate candidate, UUID snapshotId) {
        var codeSnippets = new ArrayList<String>();
        var logPatterns = new ArrayList<String>();

        // Build a combined query from all steps to avoid N+1 retrieval calls
        var combinedQuery = candidate.steps().stream()
            .map(this::buildRetrievalQuery)
            .collect(Collectors.joining(" | "));

        var result = retrieval.retrieve(new RetrievalRequest(
            snapshotId, combinedQuery, RetrievalRequest.RetrievalScope.BOTH,
            candidate.steps().size() * 3,
            Optional.empty(), Optional.of(2)
        ));

        result.documents().forEach(doc -> {
            if (doc.source() == RankedDocument.DocumentSource.VECTOR_CODE
                || doc.source() == RankedDocument.DocumentSource.BM25_CODE) {
                codeSnippets.add(doc.content());
            } else {
                logPatterns.add(doc.content());
            }
        });

        return new FlowCandidate(
            candidate.candidateId(), candidate.snapshotId(),
            candidate.flowName(), candidate.flowType(),
            candidate.steps(), candidate.involvedServices(),
            new FlowEvidence(codeSnippets, logPatterns, List.of(), List.of(), List.of(), Map.of()),
            candidate.confidenceScore(),
            candidate.complexity()
        );
    }

    /**
     * Score confidence based on evidence strength.
     */
    private FlowCandidate scoreConfidence(FlowCandidate candidate) {
        double score = 0.0;

        // More code evidence → higher confidence
        score += Math.min(candidate.evidence().codeSnippets().size() * 0.1, 0.4);

        // Log patterns supporting the flow
        score += Math.min(candidate.evidence().logPatterns().size() * 0.05, 0.2);

        // Sequential patterns matching
        score += Math.min(candidate.evidence().sequencePatterns().size() * 0.15, 0.3);

        // Graph path exists
        if (!candidate.evidence().graphPaths().isEmpty()) score += 0.1;

        return new FlowCandidate(
            candidate.candidateId(), candidate.snapshotId(),
            candidate.flowName(), candidate.flowType(),
            candidate.steps(), candidate.involvedServices(),
            candidate.evidence(),
            Math.min(score, 1.0),
            candidate.complexity()
        );
    }

    private FlowCandidate.FlowComplexity assessComplexity(List<FlowStep> steps) {
        int serviceCount = (int) steps.stream().map(FlowStep::serviceName).distinct().count();
        boolean hasReactive = steps.stream()
            .anyMatch(s -> s.reactiveComplexity() != ReactiveComplexity.NONE);
        boolean hasAsync = steps.stream()
            .anyMatch(s -> s.stepType() == FlowStep.StepType.KAFKA_PRODUCE);

        if (serviceCount >= 5 || (hasReactive && hasAsync)) return FlowCandidate.FlowComplexity.VERY_HIGH;
        if (serviceCount >= 3 || hasAsync) return FlowCandidate.FlowComplexity.HIGH;
        if (serviceCount >= 2) return FlowCandidate.FlowComplexity.MEDIUM;
        return FlowCandidate.FlowComplexity.LOW;
    }

    private String generateFlowName(List<FlowStep> steps) {
        if (steps.isEmpty()) return "unknown-flow";
        String first = steps.getFirst().serviceName();
        String last = steps.getLast().serviceName();
        return first + "-to-" + last;
    }

    private FlowStep chainNodeToFlowStep(Neo4jGraphQueryService.ChainNode node) {
        return new FlowStep(
            node.serviceName(),
            node.methodName(),
            node.httpMethod(),
            node.path(),
            FlowStep.StepType.INTERNAL_CALL,
            ReactiveComplexity.NONE,
            Map.of("source", "graph-chain")
        );
    }

    private String buildRetrievalQuery(FlowStep step) {
        return "%s %s %s".formatted(
            step.serviceName(),
            step.methodName() != null ? step.methodName() : "",
            step.path() != null ? step.path() : ""
        ).trim();
    }

    private List<FlowCandidate> mergeOverlappingFlows(List<FlowCandidate> candidates) {
        var merged = new ArrayList<FlowCandidate>();
        var used = new HashSet<Integer>();
        for (int i = 0; i < candidates.size(); i++) {
            if (used.contains(i)) continue;
            var current = candidates.get(i);
            var currentServices = current.steps().stream()
                .map(FlowStep::serviceName).collect(Collectors.toSet());
            for (int j = i + 1; j < candidates.size(); j++) {
                if (used.contains(j)) continue;
                var other = candidates.get(j);
                var otherServices = other.steps().stream()
                    .map(FlowStep::serviceName).collect(Collectors.toSet());
                long overlap = currentServices.stream().filter(otherServices::contains).count();
                if (overlap >= Math.min(currentServices.size(), otherServices.size()) * 0.7) {
                    used.add(j);
                }
            }
            merged.add(current);
        }
        return merged;
    }
}
```

### 19.3 Dependencies

```kotlin
// libs/flow-builder/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:code-parser"))
    implementation(project(":libs:graph"))
    implementation(project(":libs:retrieval"))
    implementation(project(":libs:pattern-mining"))
    implementation(project(":libs:anomaly"))
}
```

### AKS Deployment Context

This module is compiled into the FlowForge pipeline runner image (`flowforgeacr.azurecr.io/flowforge-pipeline:latest`) and executes as an Argo Workflow DAG task in the `flowforge` namespace (see Stage 28). It does not require its own Kubernetes Deployment.

**In-cluster service DNS names used:**

| Service | DNS | Port |
|---|---|---|
| PostgreSQL | `flowforge-pg-postgresql.flowforge-infra.svc.cluster.local` | 5432 |
| OpenSearch | `flowforge-opensearch.flowforge-infra.svc.cluster.local` | 9200 |
| Qdrant | `flowforge-qdrant.flowforge-infra.svc.cluster.local` | 6334 |
| Neo4j | `flowforge-neo4j.flowforge-infra.svc.cluster.local` | 7687 |

**Argo task resource class:** CPU (`cpupool` node selector)

---

## Testing & Verification Strategy

### Unit Tests

**`FlowCandidateBuilderTest`** — mock graph, retrieval, and mining; test flow construction and scoring.

```java
@ExtendWith(MockitoExtension.class)
class FlowCandidateBuilderTest {

    @Mock Neo4jGraphQueryService graphQuery;
    @Mock HybridRetrievalService retrieval;
    @Mock PatternMiningService patternMining;
    @Mock MinioStorageClient minio;
    @Mock MeterRegistry meterRegistry;
    @Mock Counter counter;

    @InjectMocks FlowCandidateBuilder builder;

    @BeforeEach
    void stubMetrics() {
        when(meterRegistry.counter(anyString())).thenReturn(counter);
    }

    @Test
    void buildHttpFlows_createsFlowFromCallChain() {
        when(graphQuery.findEntryPointServices()).thenReturn(List.of("api-gateway"));
        when(graphQuery.getCallChainsFrom("api-gateway", 5)).thenReturn(List.of(
            TestFixtures.callChain("api-gateway", "booking-service", "payment-service")
        ));
        when(retrieval.retrieve(any())).thenReturn(TestFixtures.emptyRetrievalResult());

        var candidates = builder.buildCandidates(TestFixtures.SNAPSHOT_ID);

        assertThat(candidates).isNotEmpty();
        var flow = candidates.get(0);
        assertThat(flow.flowType()).isEqualTo(FlowCandidate.FlowType.SYNC_REQUEST);
        assertThat(flow.involvedServices()).containsExactly(
            "api-gateway", "booking-service", "payment-service");
        assertThat(flow.steps()).hasSize(3);
    }

    @Test
    void buildKafkaFlows_createsAsyncEventFlow() {
        when(graphQuery.findEntryPointServices()).thenReturn(List.of());
        when(graphQuery.findKafkaTopicsWithConnections()).thenReturn(List.of(
            Map.of("name", "booking-events",
                   "producers", List.of("booking-service"),
                   "consumers", List.of("notification-service", "analytics-service"))
        ));
        when(retrieval.retrieve(any())).thenReturn(TestFixtures.emptyRetrievalResult());

        var candidates = builder.buildCandidates(TestFixtures.SNAPSHOT_ID);

        var kafkaFlows = candidates.stream()
            .filter(c -> c.flowType() == FlowCandidate.FlowType.ASYNC_EVENT)
            .toList();
        assertThat(kafkaFlows).isNotEmpty();
    }

    @Test
    void buildHttpFlows_skipsSingleStepChains() {
        when(graphQuery.findEntryPointServices()).thenReturn(List.of("api-gateway"));
        when(graphQuery.getCallChainsFrom("api-gateway", 5)).thenReturn(List.of(
            TestFixtures.callChain("api-gateway") // only 1 step
        ));

        var candidates = builder.buildCandidates(TestFixtures.SNAPSHOT_ID);
        var httpFlows = candidates.stream()
            .filter(c -> c.flowType() == FlowCandidate.FlowType.SYNC_REQUEST)
            .toList();
        assertThat(httpFlows).isEmpty();
    }

    @Test
    void mergeOverlappingFlows_deduplicatesIdenticalServiceChains() {
        when(graphQuery.findEntryPointServices()).thenReturn(List.of("gw"));
        when(graphQuery.getCallChainsFrom("gw", 5)).thenReturn(List.of(
            TestFixtures.callChain("gw", "svc-a", "svc-b"),
            TestFixtures.callChain("gw", "svc-a", "svc-b") // duplicate
        ));
        when(retrieval.retrieve(any())).thenReturn(TestFixtures.emptyRetrievalResult());

        var candidates = builder.buildCandidates(TestFixtures.SNAPSHOT_ID);
        long syncFlows = candidates.stream()
            .filter(c -> c.flowType() == FlowCandidate.FlowType.SYNC_REQUEST).count();
        assertThat(syncFlows).isEqualTo(1);
    }

    @Test
    void enrichWithEvidence_usesBatchedQuery_notPerStep() {
        when(graphQuery.findEntryPointServices()).thenReturn(List.of("gw"));
        when(graphQuery.getCallChainsFrom("gw", 5)).thenReturn(List.of(
            TestFixtures.callChain("gw", "svc-a", "svc-b", "svc-c")
        ));
        when(retrieval.retrieve(any())).thenReturn(TestFixtures.emptyRetrievalResult());

        builder.buildCandidates(TestFixtures.SNAPSHOT_ID);

        // Single retrieval call per candidate, not one per step
        verify(retrieval, times(1)).retrieve(any());
    }
}
```

**`FlowScoringTest`** — test confidence scoring formulas in isolation.

```java
class FlowScoringTest {

    @Test
    void scoreConfidence_maxesAtOne() {
        var evidence = new FlowEvidence(
            Collections.nCopies(20, "code"),   // 20 × 0.1 = capped at 0.4
            Collections.nCopies(20, "log"),    // 20 × 0.05 = capped at 0.2
            List.of("path"),                   // +0.1
            Collections.nCopies(10, pattern),  // 10 × 0.15 = capped at 0.3
            List.of(), Map.of()
        );
        var candidate = TestFixtures.flowCandidateWith(evidence);
        var scored = invokeScoreConfidence(candidate);
        assertThat(scored.confidenceScore()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void scoreConfidence_zeroEvidence_givesZeroScore() {
        var candidate = TestFixtures.flowCandidateWith(FlowEvidence.empty());
        var scored = invokeScoreConfidence(candidate);
        assertThat(scored.confidenceScore()).isEqualTo(0.0);
    }

    @Test
    void scoreConfidence_codeSnippetsCappedAtPointFour() {
        var evidence = new FlowEvidence(
            Collections.nCopies(100, "code"), List.of(), List.of(),
            List.of(), List.of(), Map.of()
        );
        var scored = invokeScoreConfidence(TestFixtures.flowCandidateWith(evidence));
        assertThat(scored.confidenceScore()).isEqualTo(0.4);
    }
}
```

**`FlowComplexityTest`** — test `assessComplexity` rules.

```java
class FlowComplexityTest {

    @Test
    void veryHigh_whenFiveOrMoreServicesInvolved() {
        var steps = TestFixtures.flowSteps("s1", "s2", "s3", "s4", "s5");
        assertThat(assessComplexity(steps)).isEqualTo(FlowComplexity.VERY_HIGH);
    }

    @Test
    void veryHigh_whenReactiveAndAsync() {
        var steps = List.of(
            TestFixtures.flowStep("svc-a", StepType.HTTP_ENDPOINT, ReactiveComplexity.HIGH),
            TestFixtures.flowStep("svc-b", StepType.KAFKA_PRODUCE, ReactiveComplexity.NONE)
        );
        assertThat(assessComplexity(steps)).isEqualTo(FlowComplexity.VERY_HIGH);
    }

    @Test
    void medium_whenTwoServicesNoAsync() {
        var steps = TestFixtures.flowSteps("svc-a", "svc-b");
        assertThat(assessComplexity(steps)).isEqualTo(FlowComplexity.MEDIUM);
    }

    @Test
    void low_whenSingleService() {
        var steps = TestFixtures.flowSteps("svc-a");
        assertThat(assessComplexity(steps)).isEqualTo(FlowComplexity.LOW);
    }
}
```

### Integration Tests

**`FlowCandidateBuilderIntegrationTest`** — Neo4j Testcontainer with seeded graph, real retrieval mocked at HTTP level.

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("integration")
class FlowCandidateBuilderIntegrationTest {

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5.26-community")
        .withoutAuthentication();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
    }

    @Autowired FlowCandidateBuilder builder;
    @Autowired Driver neo4jDriver;
    @MockitoBean HybridRetrievalService retrieval;

    @BeforeEach
    void seedGraph() {
        try (var session = neo4jDriver.session()) {
            session.run(TestFixtures.loadCypher("fixtures/flow-builder-graph.cypher"));
        }
        when(retrieval.retrieve(any())).thenReturn(TestFixtures.emptyRetrievalResult());
    }

    @Test
    void buildCandidates_fromSeededGraph_producesExpectedFlows() {
        var candidates = builder.buildCandidates(TestFixtures.SNAPSHOT_ID);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates).anyMatch(c ->
            c.flowType() == FlowCandidate.FlowType.SYNC_REQUEST);
        assertThat(candidates).allMatch(c ->
            c.confidenceScore() >= 0.0 && c.confidenceScore() <= 1.0);
    }

    @Test
    void buildCandidates_storesEvidenceInMinio() {
        builder.buildCandidates(TestFixtures.SNAPSHOT_ID);
        // Verify MinIO interaction via mock or check evidence bucket
    }

    @Test
    void buildCandidates_emptyGraph_returnsEmptyCandidates() {
        try (var session = neo4jDriver.session()) {
            session.run("MATCH (n) DETACH DELETE n");
        }
        var candidates = builder.buildCandidates(TestFixtures.SNAPSHOT_ID);
        assertThat(candidates).isEmpty();
    }
}
```

### Test Fixtures & Sample Data

| Fixture file | Description |
|---|---|
| `src/test/resources/fixtures/flow-builder-graph.cypher` | Cypher script seeding: 5 services, 3 HTTP call chains (depths 2–4), 2 Kafka topics with producers/consumers, endpoint nodes with annotations |
| `src/test/resources/fixtures/flow-steps.json` | 15 `FlowStep` records covering all `StepType` variants and reactive complexities |
| `src/test/resources/fixtures/flow-evidence-samples.json` | Pre-built `FlowEvidence` objects with code snippets, log patterns, and graph paths for scoring tests |
| `TestFixtures.java` | Factory methods: `callChain(services...)`, `flowSteps(services...)`, `flowStep(svc, type, reactiveComplexity)`, `flowCandidateWith(evidence)`, `emptyRetrievalResult()`, `loadCypher(path)` |

### Mocking Strategy

| Dependency | Mock or Real | Rationale |
|---|---|---|
| `Neo4jGraphQueryService` | **Mock** in unit tests, **Neo4j Testcontainer** in integration | Unit tests focus on builder logic; integration validates Cypher queries |
| `HybridRetrievalService` | **Mock** always (even in integration) | Avoid cascading Qdrant/OpenSearch containers; evidence enrichment tested at retrieval stage |
| `PatternMiningService` | **Mock** | Return canned sequential patterns |
| `MinioStorageClient` | **Mock** | Verify evidence path and payload structure |
| `MeterRegistry` | **SimpleMeterRegistry** | Lightweight counter/timer stubs |

### CI/CD Considerations

- **Test tags**: `@Tag("unit")` for builder, scoring, and complexity tests; `@Tag("integration")` for Neo4j-backed tests.
- **Docker images required**: `neo4j:5.26-community` for integration tests.
- **Graph seeding**: Keep Cypher fixtures idempotent with `MERGE` instead of `CREATE` to support repeated test runs without cleanup.
- **Gradle filtering**: `./gradlew :libs:flow-builder:test -PincludeTags=unit` for fast CI. Integration tests require Docker socket.
- **Test isolation**: Use `@BeforeEach` to clear and re-seed the Neo4j graph between tests (`MATCH (n) DETACH DELETE n` followed by seed script).
- **Scoring regression**: Pin expected confidence scores for known fixtures in a parameterized test to catch formula drift.

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| HTTP flows | Graph with 3-service chain | SYNC_REQUEST flow built |
| Kafka flows | Topic with producer + consumer | ASYNC_EVENT flow built |
| Error flows | Anomaly episodes present | ERROR_HANDLING flow built |
| Pattern flows | PrefixSpan patterns | Flows from patterns |
| Step ordering | Multi-step flow | Steps in correct order |
| Deduplication | Overlapping HTTP + pattern flows | Merged into single flow |
| Evidence enrichment | Flow with 3 services | Code + log evidence per step |
| Confidence scoring | Flow with strong evidence | Score > 0.5 |
| Complexity assessment | 5-service async flow | VERY_HIGH complexity |
| Flow naming | Auto-generated names | Human-readable names |
| MinIO storage | Run builder | flow-candidates/{snapshotId}.json |
| Empty graph | No topology | Empty candidate list |

## Files to create

- `libs/flow-builder/build.gradle.kts`
- `libs/flow-builder/src/main/java/com/flowforge/flow/model/FlowCandidate.java`
- `libs/flow-builder/src/main/java/com/flowforge/flow/model/FlowStep.java`
- `libs/flow-builder/src/main/java/com/flowforge/flow/model/FlowEvidence.java`
- `libs/flow-builder/src/main/java/com/flowforge/flow/builder/FlowCandidateBuilder.java`
- `libs/flow-builder/src/test/java/.../FlowCandidateBuilderTest.java`

## Depends on

- Stage 11 (Neo4j graph)
- Stage 12 (anomaly episodes)
- Stage 13 (sequential patterns)
- Stage 18 (hybrid retrieval)

## Produces

- Ranked flow candidates with evidence for LLM synthesis
- Flow candidate evidence stored in MinIO
