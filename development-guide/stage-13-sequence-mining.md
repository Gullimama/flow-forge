# Stage 13 — Sequential Pattern Mining (SPMF PrefixSpan)

## Goal

Discover recurring sequential patterns in inter-service call chains and log event sequences using **SPMF** — the premier Java-native sequential pattern mining library. Identify common flow patterns, error propagation sequences, and temporal correlations across the microservice estate.

> **Why SPMF?** SPMF is a comprehensive, pure-Java library offering 250+ data mining algorithms including PrefixSpan, SPAM, BIDE+, and CM-SPADE. Unlike Python PrefixSpan packages, SPMF is mature (10+ years), heavily cited in academic literature, and runs natively on the JVM with no FFI overhead.

## Prerequisites

- Stage 09 (parsed log events with trace context)
- Stage 10 (topology edges — service call graph)
- Stage 11 (Neo4j graph)

## What to build

### 13.1 Sequence extraction

```java
@Component
public class CallSequenceExtractor {

    /**
     * A single item in a sequence — represents an event at a service.
     */
    public record SequenceItem(
        String serviceName,
        String eventType,       // ENDPOINT_CALL, KAFKA_PRODUCE, LOG_ERROR, etc.
        String detail           // endpoint path, topic name, template ID
    ) {
        /** Encode as SPMF-compatible integer ID. */
        /** Encode using a thread-safe counter to avoid race conditions. */
        public int encode(Map<SequenceItem, Integer> itemMap, AtomicInteger counter) {
            return itemMap.computeIfAbsent(this, k -> counter.incrementAndGet());
        }
    }

    /**
     * Extract call sequences from trace-correlated log events.
     */
    public List<List<SequenceItem>> extractFromTraces(
            List<ParsedLogEvent> events,
            TopologyGraph topology) {

        // Group events by traceId
        var traceGroups = events.stream()
            .filter(e -> e.traceId().isPresent())
            .collect(Collectors.groupingBy(e -> e.traceId().orElseThrow()));

        return traceGroups.values().stream()
            .filter(group -> group.size() >= 2)  // Need at least 2 events
            .map(group -> group.stream()
                .sorted(Comparator.comparing(ParsedLogEvent::timestamp))
                .map(e -> new SequenceItem(
                    e.serviceName(),
                    classifyEventType(e),
                    extractDetail(e)
                ))
                .toList()
            )
            .toList();
    }

    /**
     * Extract sequences from temporal co-occurrence (no trace context).
     */
    public List<List<SequenceItem>> extractFromTemporalWindows(
            List<ParsedLogEvent> events,
            Duration windowSize) {

        var sorted = events.stream()
            .sorted(Comparator.comparing(ParsedLogEvent::timestamp))
            .toList();

        var sequences = new ArrayList<List<SequenceItem>>();
        var windowStart = sorted.getFirst().timestamp();

        while (windowStart.isBefore(sorted.getLast().timestamp())) {
            var windowEnd = windowStart.plus(windowSize);
            var windowEvents = filterWindow(sorted, windowStart, windowEnd);

            if (windowEvents.size() >= 2) {
                sequences.add(windowEvents.stream()
                    .map(e -> new SequenceItem(e.serviceName(),
                        classifyEventType(e), extractDetail(e)))
                    .toList());
            }
            windowStart = windowEnd;
        }

        return sequences;
    }
}
```

### 13.2 SPMF PrefixSpan integration

```java
@Component
public class SequencePatternMiner {

    private final double minSupport;         // e.g., 0.05 (5% of sequences)
    private final int maxPatternLength;      // e.g., 10

    /**
     * Run PrefixSpan algorithm on extracted sequences.
     * SPMF uses file-based I/O — sequences are written to a temp file,
     * PrefixSpan runs with input/output files, and results are parsed back.
     */
    public List<DiscoveredPattern> mine(
            List<List<CallSequenceExtractor.SequenceItem>> sequences,
            double minSupportRatio) {

        if (sequences.isEmpty()) return List.of();

        // 1. Build item encoding
        var itemMap = new ConcurrentHashMap<CallSequenceExtractor.SequenceItem, Integer>();
        var reverseMap = new ConcurrentHashMap<Integer, CallSequenceExtractor.SequenceItem>();
        var idCounter = new AtomicInteger(0);

        // 2. Encode sequences as int arrays
        var encodedSequences = new ArrayList<int[]>();
        for (var seq : sequences) {
            var encoded = new int[seq.size()];
            for (int i = 0; i < seq.size(); i++) {
                int id = seq.get(i).encode(itemMap, idCounter);
                encoded[i] = id;
                reverseMap.put(id, seq.get(i));
            }
            encodedSequences.add(encoded);
        }

        // 3. Run PrefixSpan via file I/O wrapper
        int minSupport = Math.max(1, (int) (sequences.size() * minSupportRatio));
        try {
            var frequentSequences = runPrefixSpan(encodedSequences, minSupport);

            // 4. Decode patterns
            return frequentSequences.stream()
                .map(fs -> decodePattern(fs, reverseMap, sequences.size()))
                .sorted(Comparator.comparingDouble(DiscoveredPattern::support).reversed())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("PrefixSpan file I/O failed", e);
        }
    }

    private List<FrequentSequence> runPrefixSpan(List<int[]> encodedSequences,
                                                  int minSupport) throws IOException {
        Path inputFile = Files.createTempFile("spmf-input-", ".txt");
        Path outputFile = Files.createTempFile("spmf-output-", ".txt");
        try {
            // Write sequences in SPMF format: "item1 -1 item2 -1 item3 -1 -2"
            try (var writer = Files.newBufferedWriter(inputFile)) {
                for (int[] sequence : encodedSequences) {
                    var sb = new StringBuilder();
                    for (int item : sequence) {
                        sb.append(item).append(" -1 ");
                    }
                    sb.append("-2");
                    writer.write(sb.toString());
                    writer.newLine();
                }
            }

            var algo = new AlgoPrefixSpan();
            algo.setMaximumPatternLength(maxPatternLength);
            algo.runAlgorithm(inputFile.toString(), outputFile.toString(), minSupport);

            // Parse output: each line is "item1 -1 item2 -1 #SUP: count"
            return Files.readAllLines(outputFile).stream()
                .filter(line -> !line.isBlank())
                .map(this::parseOutputLine)
                .toList();
        } finally {
            Files.deleteIfExists(inputFile);
            Files.deleteIfExists(outputFile);
        }
    }

    private FrequentSequence parseOutputLine(String line) {
        String[] parts = line.split("#SUP:");
        int support = Integer.parseInt(parts[1].trim());
        int[] items = Arrays.stream(parts[0].trim().split("\\s+"))
            .filter(s -> !s.equals("-1"))
            .mapToInt(Integer::parseInt)
            .toArray();
        return new FrequentSequence(items, support);
    }

    private record FrequentSequence(int[] items, int support) {}

    private DiscoveredPattern decodePattern(
            FrequentSequence frequent,
            Map<Integer, CallSequenceExtractor.SequenceItem> reverseMap,
            int totalSequences) {

        var items = Arrays.stream(frequent.items())
            .mapToObj(reverseMap::get)
            .toList();

        return new DiscoveredPattern(
            UUID.randomUUID(),
            items,
            frequent.support(),
            (double) frequent.support() / totalSequences,
            classifyPatternType(items)
        );
    }

    public record DiscoveredPattern(
        UUID patternId,
        List<CallSequenceExtractor.SequenceItem> items,
        int absoluteSupport,
        double support,
        PatternType patternType
    ) {}

    public enum PatternType {
        NORMAL_FLOW,          // Common happy-path call chain
        ERROR_PROPAGATION,    // Error spreading across services
        RETRY_PATTERN,        // Repeated calls to same service
        CASCADE_FAILURE,      // Sequential failures across services
        ASYNC_FANOUT          // Kafka fanout patterns
    }
}
```

### 13.3 Pattern analysis and classification

```java
@Component
public class PatternAnalyzer {

    /**
     * Enrich patterns with domain-specific analysis.
     */
    public List<EnrichedPattern> analyzePatterns(
            List<SequencePatternMiner.DiscoveredPattern> patterns,
            TopologyGraph topology) {

        return patterns.stream()
            .map(p -> enrichPattern(p, topology))
            .toList();
    }

    private EnrichedPattern enrichPattern(
            SequencePatternMiner.DiscoveredPattern pattern,
            TopologyGraph topology) {

        var services = pattern.items().stream()
            .map(CallSequenceExtractor.SequenceItem::serviceName)
            .distinct()
            .toList();

        var hasErrors = pattern.items().stream()
            .anyMatch(i -> "LOG_ERROR".equals(i.eventType()));

        var crossesServiceBoundary = services.size() > 1;

        var involvesKafka = pattern.items().stream()
            .anyMatch(i -> "KAFKA_PRODUCE".equals(i.eventType())
                        || "KAFKA_CONSUME".equals(i.eventType()));

        return new EnrichedPattern(
            pattern,
            services,
            crossesServiceBoundary,
            hasErrors,
            involvesKafka,
            generatePatternDescription(pattern)
        );
    }

    private String generatePatternDescription(SequencePatternMiner.DiscoveredPattern pattern) {
        var sb = new StringBuilder();
        for (int i = 0; i < pattern.items().size(); i++) {
            var item = pattern.items().get(i);
            if (i > 0) sb.append(" → ");
            sb.append(item.serviceName()).append("::").append(item.eventType());
            if (!item.detail().isEmpty()) {
                sb.append("(").append(item.detail()).append(")");
            }
        }
        return sb.toString();
    }
}

public record EnrichedPattern(
    SequencePatternMiner.DiscoveredPattern pattern,
    List<String> involvedServices,
    boolean crossesServiceBoundary,
    boolean hasErrors,
    boolean involvesKafka,
    String description
) {}
```

### 13.4 Pattern mining service

```java
@Service
public class PatternMiningService {

    private final CallSequenceExtractor sequenceExtractor;
    private final SequencePatternMiner miner;
    private final PatternAnalyzer analyzer;
    private final OpenSearchClientWrapper openSearch;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    private static final double MIN_SUPPORT = 0.03;  // 3%

    /**
     * Mine patterns from a snapshot's log events and topology.
     */
    public PatternMiningResult minePatterns(UUID snapshotId,
                                             List<ParsedLogEvent> events,
                                             TopologyGraph topology) {
        // 1. Extract sequences from traces
        var traceSequences = sequenceExtractor.extractFromTraces(events, topology);

        // 2. Extract sequences from temporal windows (fallback)
        var temporalSequences = sequenceExtractor.extractFromTemporalWindows(
            events, Duration.ofSeconds(30));

        // 3. Combine and deduplicate
        var allSequences = new ArrayList<>(traceSequences);
        allSequences.addAll(temporalSequences);

        // 4. Run PrefixSpan
        var patterns = miner.mine(allSequences, MIN_SUPPORT);

        // 5. Analyze and enrich
        var enrichedPatterns = analyzer.analyzePatterns(patterns, topology);

        // 6. Store in MinIO evidence
        minio.putJson("evidence", "patterns/" + snapshotId + ".json", enrichedPatterns);

        // 7. Metrics
        meterRegistry.counter("flowforge.patterns.discovered").increment(patterns.size());
        meterRegistry.counter("flowforge.patterns.error").increment(
            enrichedPatterns.stream().filter(EnrichedPattern::hasErrors).count());

        return new PatternMiningResult(
            traceSequences.size(),
            temporalSequences.size(),
            patterns.size(),
            enrichedPatterns.stream().filter(EnrichedPattern::crossesServiceBoundary).count(),
            enrichedPatterns.stream().filter(EnrichedPattern::hasErrors).count()
        );
    }
}

public record PatternMiningResult(
    int traceSequences,
    int temporalSequences,
    int patternsDiscovered,
    long crossServicePatterns,
    long errorPatterns
) {}
```

### 13.5 Dependencies

```kotlin
// libs/pattern-mining/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:log-parser"))
    implementation(project(":libs:topology"))
    implementation(libs.spmf)  // ca.pfv.spmf:spmf:2.60
}
```

Add to version catalog:
```toml
[versions]
spmf = "2.60"

[libraries]
spmf = { module = "ca.pfv.spmf:spmf", version.ref = "spmf" }
```

> **SPMF availability:** SPMF is not published on Maven Central. To integrate it:
> 1. Download the latest JAR from https://www.philippe-fournier-viger.com/spmf/
> 2. Install to your local Maven repo:
>    ```bash
>    mvn install:install-file -Dfile=spmf.jar -DgroupId=ca.pfv.spmf \
>        -DartifactId=spmf -Dversion=2.60 -Dpackaging=jar
>    ```
> 3. For CI/CD, publish to your org's Nexus/Artifactory, or add the JAR as a
>    flat-dir dependency in Gradle:
>    ```kotlin
>    repositories {
>        flatDir { dirs("libs/spmf") }
>    }
>    dependencies {
>        implementation(":spmf:2.60")
>    }
>    ```
> The SPMF API is academic-grade and not stable across versions. Pin the version
> and wrap all SPMF calls behind the `SequencePatternMiner` interface to isolate
> from API changes.

### AKS Deployment Context

This module is compiled into the FlowForge pipeline runner image (`flowforgeacr.azurecr.io/flowforge-pipeline:latest`) and executes as an Argo Workflow DAG task in the `flowforge` namespace (see Stage 28). It does not require its own Kubernetes Deployment.

**In-cluster service DNS names used:**

| Service | DNS | Port |
|---|---|---|
| PostgreSQL | `flowforge-pg-postgresql.flowforge-infra.svc.cluster.local` | 5432 |
| OpenSearch | `flowforge-opensearch.flowforge-infra.svc.cluster.local` | 9200 |
| Neo4j | `flowforge-neo4j.flowforge-infra.svc.cluster.local` | 7687 |

**Argo task resource class:** CPU (`cpupool` node selector) — SPMF runs natively in the JVM, no GPU required.

---

## Testing & Verification Strategy

### Unit Tests

**`CallSequenceExtractorTest`** — tests trace-based and temporal window sequence extraction.

```java
class CallSequenceExtractorTest {

    private final CallSequenceExtractor extractor = new CallSequenceExtractor();

    @Test
    void extractFromTraces_groupsByTraceId() {
        var events = List.of(
            logEvent("order-svc", "trace-1", Instant.now()),
            logEvent("payment-svc", "trace-1", Instant.now().plusMillis(50)),
            logEvent("order-svc", "trace-2", Instant.now()),
            logEvent("shipping-svc", "trace-2", Instant.now().plusMillis(100)),
            logEvent("shipping-svc", "trace-2", Instant.now().plusMillis(200))
        );
        var topology = emptyTopology();

        var sequences = extractor.extractFromTraces(events, topology);

        assertThat(sequences).hasSize(2);
        assertThat(sequences.get(0)).hasSize(2); // trace-1: 2 events
        assertThat(sequences.get(1)).hasSize(3); // trace-2: 3 events
    }

    @Test
    void extractFromTraces_filtersOutEventsWithoutTraceId() {
        var events = List.of(
            logEvent("svc-a", "trace-1", Instant.now()),
            logEvent("svc-b", "trace-1", Instant.now().plusMillis(10)),
            logEventNoTrace("svc-c", Instant.now())
        );

        var sequences = extractor.extractFromTraces(events, emptyTopology());

        assertThat(sequences).hasSize(1);
        assertThat(sequences.getFirst()).hasSize(2);
    }

    @Test
    void extractFromTraces_singleEventTrace_excluded() {
        var events = List.of(
            logEvent("svc-a", "trace-lonely", Instant.now())
        );

        var sequences = extractor.extractFromTraces(events, emptyTopology());

        assertThat(sequences).isEmpty(); // need at least 2 events per trace
    }

    @Test
    void extractFromTraces_sortsEventsByTimestamp() {
        var now = Instant.now();
        var events = List.of(
            logEvent("svc-b", "trace-1", now.plusMillis(100)),
            logEvent("svc-a", "trace-1", now)
        );

        var sequences = extractor.extractFromTraces(events, emptyTopology());

        assertThat(sequences.getFirst().get(0).serviceName()).isEqualTo("svc-a");
        assertThat(sequences.getFirst().get(1).serviceName()).isEqualTo("svc-b");
    }

    @Test
    void extractFromTemporalWindows_splitsIntoWindows() {
        var now = Instant.now();
        var events = IntStream.range(0, 10)
            .mapToObj(i -> logEventNoTrace("svc-" + (i % 3), now.plusSeconds(i * 5)))
            .toList();

        var sequences = extractor.extractFromTemporalWindows(events, Duration.ofSeconds(30));

        assertThat(sequences).isNotEmpty();
        sequences.forEach(seq -> assertThat(seq.size()).isGreaterThanOrEqualTo(2));
    }

    @Test
    void sequenceItemEncode_threadSafe_noDuplicateIds() throws Exception {
        var itemMap = new ConcurrentHashMap<CallSequenceExtractor.SequenceItem, Integer>();
        var counter = new AtomicInteger(0);

        var items = List.of(
            new CallSequenceExtractor.SequenceItem("svc-a", "ENDPOINT_CALL", "/orders"),
            new CallSequenceExtractor.SequenceItem("svc-b", "KAFKA_PRODUCE", "topic-1"),
            new CallSequenceExtractor.SequenceItem("svc-a", "ENDPOINT_CALL", "/orders") // duplicate
        );

        var executor = Executors.newFixedThreadPool(4);
        var futures = items.stream()
            .map(item -> executor.submit(() -> item.encode(itemMap, counter)))
            .toList();

        var ids = futures.stream().map(f -> {
            try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList();

        assertThat(ids.get(0)).isEqualTo(ids.get(2)); // same item → same ID
        assertThat(ids.get(0)).isNotEqualTo(ids.get(1)); // different items → different IDs
        assertThat(itemMap).hasSize(2); // only 2 unique items
        executor.shutdown();
    }
}
```

**`SequencePatternMinerTest`** — tests SPMF PrefixSpan file I/O wrapper, encoding, and decoding.

```java
class SequencePatternMinerTest {

    private final SequencePatternMiner miner = new SequencePatternMiner(0.05, 10);

    @Test
    void mine_repeatedPattern_discoveredWithHighSupport() {
        var itemA = new CallSequenceExtractor.SequenceItem("svc-a", "ENDPOINT_CALL", "/start");
        var itemB = new CallSequenceExtractor.SequenceItem("svc-b", "ENDPOINT_CALL", "/process");
        var itemC = new CallSequenceExtractor.SequenceItem("svc-c", "ENDPOINT_CALL", "/finish");

        // A→B→C repeated in 80% of sequences
        var sequences = new ArrayList<List<CallSequenceExtractor.SequenceItem>>();
        for (int i = 0; i < 80; i++) sequences.add(List.of(itemA, itemB, itemC));
        for (int i = 0; i < 20; i++) sequences.add(List.of(itemA, itemC)); // variant

        var patterns = miner.mine(sequences, 0.05);

        assertThat(patterns).isNotEmpty();
        var topPattern = patterns.getFirst(); // highest support
        assertThat(topPattern.support()).isGreaterThan(0.5);
    }

    @Test
    void mine_decodesPatternsBackToOriginalItems() {
        var itemA = new CallSequenceExtractor.SequenceItem("order-svc", "ENDPOINT_CALL", "/orders");
        var itemB = new CallSequenceExtractor.SequenceItem("payment-svc", "ENDPOINT_CALL", "/pay");

        var sequences = List.<List<CallSequenceExtractor.SequenceItem>>of(
            List.of(itemA, itemB), List.of(itemA, itemB), List.of(itemA, itemB)
        );

        var patterns = miner.mine(sequences, 0.5);

        assertThat(patterns).isNotEmpty();
        var decoded = patterns.getFirst().items();
        assertThat(decoded).contains(itemA);
    }

    @Test
    void mine_resultsSortedBySupportDescending() {
        var items = IntStream.range(0, 5)
            .mapToObj(i -> new CallSequenceExtractor.SequenceItem("svc-" + i, "CALL", "/ep"))
            .toList();

        var sequences = new ArrayList<List<CallSequenceExtractor.SequenceItem>>();
        for (int i = 0; i < 50; i++) sequences.add(List.of(items.get(0), items.get(1)));
        for (int i = 0; i < 30; i++) sequences.add(List.of(items.get(2), items.get(3)));

        var patterns = miner.mine(sequences, 0.05);

        for (int i = 1; i < patterns.size(); i++) {
            assertThat(patterns.get(i).support()).isLessThanOrEqualTo(patterns.get(i - 1).support());
        }
    }

    @Test
    void mine_emptyInput_returnsEmptyList() {
        var patterns = miner.mine(List.of(), 0.05);
        assertThat(patterns).isEmpty();
    }
}
```

**`PatternAnalyzerTest`** — tests pattern classification logic.

```java
class PatternAnalyzerTest {

    private final PatternAnalyzer analyzer = new PatternAnalyzer();

    @Test
    void analyzePatterns_errorPropagation_identifiedCorrectly() {
        var pattern = discoveredPattern(List.of(
            seqItem("svc-a", "LOG_ERROR", "timeout"),
            seqItem("svc-b", "LOG_ERROR", "connection refused"),
            seqItem("svc-c", "LOG_ERROR", "downstream failure")
        ));

        var result = analyzer.analyzePatterns(List.of(pattern), emptyTopology());

        assertThat(result.getFirst().hasErrors()).isTrue();
        assertThat(result.getFirst().crossesServiceBoundary()).isTrue();
        assertThat(pattern.patternType()).isEqualTo(PatternType.ERROR_PROPAGATION);
    }

    @Test
    void analyzePatterns_kafkaFanout_flaggedAsKafkaInvolved() {
        var pattern = discoveredPattern(List.of(
            seqItem("svc-a", "KAFKA_PRODUCE", "orders-topic"),
            seqItem("svc-b", "KAFKA_CONSUME", "orders-topic"),
            seqItem("svc-c", "KAFKA_CONSUME", "orders-topic")
        ));

        var result = analyzer.analyzePatterns(List.of(pattern), emptyTopology());

        assertThat(result.getFirst().involvesKafka()).isTrue();
    }

    @Test
    void analyzePatterns_singleServicePattern_notCrossService() {
        var pattern = discoveredPattern(List.of(
            seqItem("svc-a", "ENDPOINT_CALL", "/step1"),
            seqItem("svc-a", "ENDPOINT_CALL", "/step2")
        ));

        var result = analyzer.analyzePatterns(List.of(pattern), emptyTopology());

        assertThat(result.getFirst().crossesServiceBoundary()).isFalse();
    }

    @Test
    void generatePatternDescription_producesReadableArrowFormat() {
        var pattern = discoveredPattern(List.of(
            seqItem("order-svc", "ENDPOINT_CALL", "/orders"),
            seqItem("payment-svc", "ENDPOINT_CALL", "/pay")
        ));

        var result = analyzer.analyzePatterns(List.of(pattern), emptyTopology());

        assertThat(result.getFirst().description())
            .contains("order-svc::ENDPOINT_CALL(/orders)")
            .contains("→")
            .contains("payment-svc::ENDPOINT_CALL(/pay)");
    }

    @Test
    void analyzePatterns_retryPattern_classifiedCorrectly() {
        var pattern = discoveredPattern(List.of(
            seqItem("svc-a", "ENDPOINT_CALL", "/pay"),
            seqItem("svc-a", "ENDPOINT_CALL", "/pay"),
            seqItem("svc-a", "ENDPOINT_CALL", "/pay")
        ));

        assertThat(pattern.patternType()).isEqualTo(PatternType.RETRY_PATTERN);
    }
}
```

### Integration Tests

**`PatternMiningServiceIntegrationTest`** — exercises the full mining pipeline with mocked external stores.

```java
@SpringBootTest
@Tag("integration")
class PatternMiningServiceIntegrationTest {

    @MockitoBean OpenSearchClientWrapper openSearch;
    @MockitoBean MinioStorageClient minio;
    @Autowired PatternMiningService service;

    @Test
    void minePatterns_fullPipeline_discoversAndStoresPatterns() {
        var snapshotId = UUID.randomUUID();
        var events = loadFixture("log-events-with-traces.json", ParsedLogEvent.class);
        var topology = loadFixture("topology-small.json", TopologyGraph.class);

        var result = service.minePatterns(snapshotId, events, topology);

        assertThat(result.traceSequences()).isGreaterThan(0);
        assertThat(result.patternsDiscovered()).isGreaterThan(0);
        verify(minio).putJson(eq("evidence"), contains("patterns/" + snapshotId), any());
    }

    @Test
    void minePatterns_withErrorPropagation_detectsErrorPatterns() {
        var snapshotId = UUID.randomUUID();
        var events = loadFixture("log-events-error-cascade.json", ParsedLogEvent.class);
        var topology = loadFixture("topology-small.json", TopologyGraph.class);

        var result = service.minePatterns(snapshotId, events, topology);

        assertThat(result.errorPatterns()).isGreaterThan(0);
    }
}
```

### Test Fixtures & Sample Data

| Fixture file | Description |
|---|---|
| `src/test/resources/fixtures/log-events-with-traces.json` | 100 events across 3 services, 15 trace IDs, with a repeating A→B→C call pattern (10+ occurrences) |
| `src/test/resources/fixtures/log-events-error-cascade.json` | 50 events simulating error propagation: svc-a error → svc-b error → svc-c error across 5 traces |
| `src/test/resources/fixtures/topology-small.json` | 3 services, Kafka topic, edges — reused from Stage 11 fixtures |
| `src/test/resources/fixtures/spmf-sequences-encoded.txt` | Pre-encoded SPMF input for direct PrefixSpan testing without the extractor |

Sample data guidelines:
- Trace-based fixtures need a consistent `traceId` across events and realistic timestamps (increasing within a trace).
- Include at least 3 distinct pattern types: a normal flow (repeated A→B→C), an error propagation sequence, and a retry pattern (A→B→B→B).
- Minimum support in test data should be 5%+ for at least one pattern to ensure PrefixSpan discovers it.

### Mocking Strategy

| Dependency | Unit tests | Integration tests |
|---|---|---|
| `CallSequenceExtractor` | Real instance (pure logic) | Real instance |
| `SequencePatternMiner` (SPMF) | Real instance — SPMF is a JAR dependency, no external service | Real instance |
| `PatternAnalyzer` | Real instance (pure computation) | Real instance |
| `OpenSearchClientWrapper` | Not used | `@MockitoBean` |
| `MinioStorageClient` | Not used | `@MockitoBean` — verify `putJson` calls |
| `TopologyGraph` | Construct in-memory | Load from fixture |
| `MeterRegistry` | `SimpleMeterRegistry` | `SimpleMeterRegistry` |

- SPMF is a local JAR with no external I/O — always use the real library in both unit and integration tests. Never mock it.
- Since SPMF is not on Maven Central, ensure the JAR is available in the test classpath via flat-dir or local Maven repository (see §13.5 in the stage guide).

### CI/CD Considerations

- **JUnit tags:** Unit tests untagged. Integration tests tagged `@Tag("integration")`.
- **Gradle filtering:**
  ```kotlin
  tasks.test { useJUnitPlatform { excludeTags("integration") } }
  tasks.register<Test>("integrationTest") {
      useJUnitPlatform { includeTags("integration") }
  }
  ```
- **SPMF JAR provisioning:** The SPMF JAR must be available to CI. Options:
  1. Publish to your org's Nexus/Artifactory and reference via `repositories { maven { url = uri("...") } }`.
  2. Commit the JAR to the repo under `libs/spmf/` and use Gradle `flatDir`.
  3. Pre-install to the CI runner's local Maven cache in a setup step.
- **No Docker needed:** This stage has no containerized infrastructure dependencies. All components are pure Java.
- **Thread safety:** The `AtomicInteger` encoding counter must be tested under concurrent access. Include a multi-threaded unit test (see `sequenceItemEncode_threadSafe_noDuplicateIds` above).
- **PrefixSpan performance:** Mining 1000+ sequences with low minSupport can take several seconds. Set a reasonable JUnit `@Timeout(30)` on mining tests to catch regressions.

## Verification

**Stage 13 sign-off requires all stages 1 through 13 to pass.** Run: `make verify`.

The verification report for stage 13 is `logs/stage-13.log`. It contains **cumulative output for stages 1–13** (Stage 1, then Stage 2, … then Stage 13 output).

| Check | How to verify | Pass criteria |
|---|---|---|
| Trace extraction | 100 events with 10 traces | 10 sequences extracted |
| Temporal extraction | 30 events in 5-min window | Window sequences created |
| Item encoding | 5 unique items | Items encoded 1-5, reversible |
| PrefixSpan run | 50 sequences, 3% min support | Patterns discovered |
| Pattern decode | Encoded pattern | Original SequenceItems recovered |
| Normal flow | Repeated A→B→C pattern | PatternType = NORMAL_FLOW |
| Error propagation | A(error)→B(error)→C(error) | PatternType = ERROR_PROPAGATION |
| Retry pattern | A→B→B→B (repeated calls) | PatternType = RETRY_PATTERN |
| Cross-service | Pattern with 3+ services | crossesServiceBoundary = true |
| Kafka fanout | Pattern with KAFKA_PRODUCE | involvesKafka = true |
| Pattern description | Any pattern | Human-readable "A::HTTP→B::KAFKA" |
| Support ordering | Multiple patterns | Sorted by support descending |
| Evidence storage | Full run | patterns/{snapshotId}.json in MinIO |

## Files to create

- `libs/pattern-mining/build.gradle.kts`
- `libs/pattern-mining/src/main/java/com/flowforge/patterns/extract/CallSequenceExtractor.java`
- `libs/pattern-mining/src/main/java/com/flowforge/patterns/mining/SequencePatternMiner.java`
- `libs/pattern-mining/src/main/java/com/flowforge/patterns/analysis/PatternAnalyzer.java`
- `libs/pattern-mining/src/main/java/com/flowforge/patterns/service/PatternMiningService.java`
- `libs/pattern-mining/src/test/java/.../CallSequenceExtractorTest.java`
- `libs/pattern-mining/src/test/java/.../SequencePatternMinerTest.java`
- `libs/pattern-mining/src/test/java/.../PatternAnalyzerTest.java`
- `libs/pattern-mining/src/test/java/.../PatternMiningServiceIntegrationTest.java`

## Depends on

- Stage 09 (parsed log events)
- Stage 10 (topology graph)

## Produces

- Discovered sequential patterns with support scores
- Error propagation and cascade failure patterns identified
- Cross-service flow patterns for synthesis stages
- Pattern evidence stored in MinIO
