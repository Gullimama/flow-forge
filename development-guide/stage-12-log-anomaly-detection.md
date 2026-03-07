# Stage 12 — Log Anomaly Detection (Smile Isolation Forest)

## Goal

Detect anomalous log patterns using **Smile** (Statistical Machine Intelligence and Learning Engine) with Isolation Forest. Feature-engineer log event streams into time-windowed feature vectors, train per-service anomaly models, score new log windows, and produce anomaly episodes stored in OpenSearch and Neo4j.

> **Why Smile?** Smile is a comprehensive, high-performance Java ML library offering Isolation Forest out-of-the-box via `smile.anomaly.IsolationForest`. Unlike Tribuo (whose anomaly module only exposes One-Class SVM via libSVM), Smile provides a native Isolation Forest with a simple `fit`/`score` API — no Python or scikit-learn needed.

## Prerequisites

- Stage 09 (parsed log events in OpenSearch)
- Stage 11 (Neo4j graph for anomaly → service linking)

## What to build

### 12.1 Feature engineering

```java
@Component
public class LogFeatureEngineer {

    private static final Duration WINDOW_SIZE = Duration.ofMinutes(5);

    public record LogFeatureVector(
        String serviceName,
        Instant windowStart,
        Instant windowEnd,
        double errorRate,           // ERROR+FATAL / total_events
        double uniqueTemplateRatio, // distinct_templates / total_events
        double eventRate,           // events_per_second
        double p99Latency,          // from Istio access logs
        double errorBurstScore,     // max consecutive errors
        double newTemplateRate,     // templates seen < 3 times
        double traceSpanRatio,      // events_with_trace / total_events
        double exceptionRate,       // events_with_exception / total_events
        int totalEvents
    ) {}

    /**
     * Convert raw log events into windowed feature vectors.
     */
    public List<LogFeatureVector> extractFeatures(String serviceName, List<ParsedLogEvent> events) {
        // Sort by timestamp
        var sorted = events.stream()
            .sorted(Comparator.comparing(ParsedLogEvent::timestamp))
            .toList();

        if (sorted.isEmpty()) return List.of();

        var vectors = new ArrayList<LogFeatureVector>();
        var windowStart = sorted.getFirst().timestamp().truncatedTo(ChronoUnit.MINUTES);
        var end = sorted.getLast().timestamp();

        while (windowStart.isBefore(end)) {
            var windowEnd = windowStart.plus(WINDOW_SIZE);
            var windowEvents = filterWindow(sorted, windowStart, windowEnd);

            if (!windowEvents.isEmpty()) {
                vectors.add(computeFeatures(serviceName, windowStart, windowEnd, windowEvents));
            }
            windowStart = windowEnd;
        }

        return vectors;
    }

    private LogFeatureVector computeFeatures(String serviceName, Instant start, Instant end,
                                              List<ParsedLogEvent> events) {
        int total = events.size();
        long errors = events.stream()
            .filter(e -> e.severity() == ParsedLogEvent.LogSeverity.ERROR
                      || e.severity() == ParsedLogEvent.LogSeverity.FATAL)
            .count();
        long uniqueTemplates = events.stream()
            .map(ParsedLogEvent::templateId)
            .distinct()
            .count();
        long withTrace = events.stream()
            .filter(e -> e.traceId().isPresent())
            .count();
        long withException = events.stream()
            .filter(e -> e.exceptionClass().isPresent())
            .count();

        double seconds = Duration.between(start, end).toSeconds();

        return new LogFeatureVector(
            serviceName, start, end,
            (double) errors / total,
            (double) uniqueTemplates / total,
            total / Math.max(seconds, 1.0),
            computeP99Latency(events),
            computeErrorBurstScore(events),
            computeNewTemplateRate(events),
            (double) withTrace / total,
            (double) withException / total,
            total
        );
    }
}
```

### 12.2 Smile Isolation Forest model

```java
import smile.anomaly.IsolationForest;

@Component
public class AnomalyDetectorModel {

    private final int numTrees;
    private final int subsampleSize;

    private IsolationForest trainedModel;

    public AnomalyDetectorModel(
            @Value("${flowforge.anomaly.num-trees:200}") int numTrees,
            @Value("${flowforge.anomaly.subsample-size:256}") int subsampleSize) {
        this.numTrees = numTrees;
        this.subsampleSize = subsampleSize;
    }

    public int getNumTrees() { return numTrees; }
    public int getSubsampleSize() { return subsampleSize; }

    /**
     * Train an Isolation Forest on historical feature vectors.
     */
    public void train(List<LogFeatureVector> features) {
        double[][] matrix = buildFeatureMatrix(features);
        this.trainedModel = IsolationForest.fit(matrix, numTrees, subsampleSize);
    }

    /**
     * Score a feature vector: higher score → more anomalous.
     * Smile scores: ~1.0 = anomalous, ~0.5 = normal, ~0.0 = not anomalous.
     */
    public double score(LogFeatureVector vector) {
        if (trainedModel == null) {
            throw new IllegalStateException("Model not trained");
        }
        return trainedModel.score(vectorToArray(vector));
    }

    /**
     * Classify as anomalous if score exceeds threshold.
     */
    public boolean isAnomalous(LogFeatureVector vector, double threshold) {
        return score(vector) >= threshold;
    }

    private double[][] buildFeatureMatrix(List<LogFeatureVector> features) {
        return features.stream()
            .map(this::vectorToArray)
            .toArray(double[][]::new);
    }

    private double[] vectorToArray(LogFeatureVector v) {
        return new double[]{
            v.errorRate(), v.uniqueTemplateRatio(), v.eventRate(),
            v.p99Latency(), v.errorBurstScore(), v.newTemplateRate(),
            v.traceSpanRatio(), v.exceptionRate()
        };
    }

    /** Serialize model to bytes (for MLflow/MinIO storage). */
    public byte[] serializeModel() throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var oos = new ObjectOutputStream(baos)) {
            oos.writeObject(trainedModel);
        }
        return baos.toByteArray();
    }

    /** Load model from bytes. */
    public void loadModel(byte[] modelBytes) throws IOException, ClassNotFoundException {
        var bais = new ByteArrayInputStream(modelBytes);
        try (var ois = new ObjectInputStream(bais)) {
            this.trainedModel = (IsolationForest) ois.readObject();
        }
    }
}
```

### 12.3 Anomaly episode builder

```java
@Component
public class AnomalyEpisodeBuilder {

    private static final Duration MERGE_GAP = Duration.ofMinutes(10);

    public record AnomalyEpisode(
        UUID episodeId,
        UUID snapshotId,
        String serviceName,
        Instant startTime,
        Instant endTime,
        AnomalySeverity severity,
        double peakScore,
        List<LogFeatureVector> windows,
        List<String> topAnomalousTemplates,
        String summary
    ) {}

    public enum AnomalySeverity { LOW, MEDIUM, HIGH, CRITICAL }

    /**
     * Group consecutive anomalous windows into episodes.
     */
    public List<AnomalyEpisode> buildEpisodes(UUID snapshotId, String serviceName,
                                                List<LogFeatureVector> vectors,
                                                AnomalyDetectorModel model,
                                                double threshold) {
        var anomalousWindows = vectors.stream()
            .filter(v -> model.isAnomalous(v, threshold))
            .sorted(Comparator.comparing(LogFeatureVector::windowStart))
            .toList();

        if (anomalousWindows.isEmpty()) return List.of();

        var episodes = new ArrayList<AnomalyEpisode>();
        var current = new ArrayList<LogFeatureVector>();
        current.add(anomalousWindows.getFirst());

        for (int i = 1; i < anomalousWindows.size(); i++) {
            var prev = anomalousWindows.get(i - 1);
            var curr = anomalousWindows.get(i);

            if (Duration.between(prev.windowEnd(), curr.windowStart()).compareTo(MERGE_GAP) <= 0) {
                current.add(curr);
            } else {
                episodes.add(buildEpisode(snapshotId, serviceName, current, model));
                current = new ArrayList<>();
                current.add(curr);
            }
        }
        episodes.add(buildEpisode(snapshotId, serviceName, current, model));

        return episodes;
    }

    private AnomalyEpisode buildEpisode(UUID snapshotId, String serviceName,
                                          List<LogFeatureVector> windows,
                                          AnomalyDetectorModel model) {
        double peakScore = windows.stream()
            .mapToDouble(model::score)
            .max().orElse(0);

        var severity = classifySeverity(peakScore);

        return new AnomalyEpisode(
            UUID.randomUUID(), snapshotId, serviceName,
            windows.getFirst().windowStart(),
            windows.getLast().windowEnd(),
            severity, peakScore, windows,
            extractTopTemplates(windows),
            "Anomaly episode: %d windows, peak score %.3f".formatted(windows.size(), peakScore)
        );
    }

    private AnomalySeverity classifySeverity(double score) {
        if (score < 0.6) return AnomalySeverity.LOW;
        if (score < 0.8) return AnomalySeverity.MEDIUM;
        if (score < 0.9) return AnomalySeverity.HIGH;
        return AnomalySeverity.CRITICAL;
    }
}
```

### 12.4 Anomaly detection service

```java
@Service
public class AnomalyDetectionService {

    private final LogFeatureEngineer featureEngineer;
    private final AnomalyDetectorModel modelTemplate;
    private final AnomalyEpisodeBuilder episodeBuilder;
    private final OpenSearchClientWrapper openSearch;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    private static final double DEFAULT_THRESHOLD = 0.65;

    /**
     * Run anomaly detection on logs for all services in a snapshot.
     */
    public AnomalyDetectionResult detectAnomalies(UUID snapshotId, List<String> serviceNames) {
        var allEpisodes = new ArrayList<AnomalyEpisodeBuilder.AnomalyEpisode>();

        for (var service : serviceNames) {
            // 1. Fetch log events from OpenSearch
            var events = fetchLogEvents(snapshotId, service);

            // 2. Feature engineering
            var features = featureEngineer.extractFeatures(service, events);

            // 3. Train a per-service model (unsupervised)
            var serviceModel = new AnomalyDetectorModel(
                modelTemplate.getNumTrees(), modelTemplate.getSubsampleSize());
            serviceModel.train(features);

            // 4. Score + build episodes
            var episodes = episodeBuilder.buildEpisodes(
                snapshotId, service, features, serviceModel, DEFAULT_THRESHOLD
            );
            allEpisodes.addAll(episodes);

            // 5. Store model in MinIO
            var modelBytes = serviceModel.serializeModel();
            minio.putBytes("models", "anomaly/" + snapshotId + "/" + service + ".smile", modelBytes);
        }

        // 6. Index episodes to OpenSearch
        var docs = allEpisodes.stream().map(this::episodeToDocument).toList();
        if (!docs.isEmpty()) {
            openSearch.bulkIndex("anomaly-episodes", docs);
        }

        // 7. Store summary in evidence
        minio.putJson("evidence", "anomaly-report/" + snapshotId + ".json",
            Map.of("snapshotId", snapshotId, "totalEpisodes", allEpisodes.size(),
                   "episodesBySeverity", groupBySeverity(allEpisodes)));

        meterRegistry.counter("flowforge.anomaly.episodes.detected").increment(allEpisodes.size());

        return new AnomalyDetectionResult(allEpisodes.size(),
            groupBySeverity(allEpisodes));
    }
}

public record AnomalyDetectionResult(
    int totalEpisodes,
    Map<AnomalyEpisodeBuilder.AnomalySeverity, Long> bySeverity
) {}
```

### 12.5 Dependencies

```kotlin
// libs/anomaly/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:log-parser"))
    implementation(libs.smile.core)    // com.github.haifengl:smile-core:3.1.1
}
```

Add to version catalog:
```toml
[versions]
smile = "3.1.1"

[libraries]
smile-core = { module = "com.github.haifengl:smile-core", version.ref = "smile" }
```

### AKS Deployment Context

This module is compiled into the FlowForge pipeline runner image (`flowforgeacr.azurecr.io/flowforge-pipeline:latest`) and executes as an Argo Workflow DAG task in the `flowforge` namespace (see Stage 28). It does not require its own Kubernetes Deployment.

**In-cluster service DNS names used:**

| Service | DNS | Port |
|---|---|---|
| PostgreSQL | `flowforge-pg-postgresql.flowforge-infra.svc.cluster.local` | 5432 |
| OpenSearch | `flowforge-opensearch.flowforge-infra.svc.cluster.local` | 9200 |
| Neo4j | `flowforge-neo4j.flowforge-infra.svc.cluster.local` | 7687 |

**Argo task resource class:** CPU (`cpupool` node selector) — Smile runs natively in the JVM, no GPU required.

---

## Testing & Verification Strategy

### Unit Tests

**`LogFeatureEngineerTest`** — tests feature vector computation in isolation. No external dependencies.

```java
class LogFeatureEngineerTest {

    private final LogFeatureEngineer engineer = new LogFeatureEngineer();

    @Test
    void extractFeatures_emptyEventList_returnsEmptyList() {
        var result = engineer.extractFeatures("order-service", List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void extractFeatures_thirtyMinutesOfLogs_producesSixWindows() {
        var events = generateEventsOverMinutes("order-service", 30, 10);
        var result = engineer.extractFeatures("order-service", events);
        assertThat(result).hasSize(6); // 30 min / 5-min window = 6
    }

    @Test
    void computeFeatures_errorRate_calculatedCorrectly() {
        var events = List.of(
            logEvent("svc", LogSeverity.INFO, "tmpl-1"),
            logEvent("svc", LogSeverity.ERROR, "tmpl-2"),
            logEvent("svc", LogSeverity.ERROR, "tmpl-3"),
            logEvent("svc", LogSeverity.FATAL, "tmpl-4")
        );
        var result = engineer.extractFeatures("svc", events);
        // 3 errors (2 ERROR + 1 FATAL) out of 4 = 0.75
        assertThat(result.getFirst().errorRate()).isCloseTo(0.75, within(0.001));
    }

    @Test
    void computeFeatures_uniqueTemplateRatio_handlesAllSameTemplate() {
        var events = IntStream.range(0, 20)
            .mapToObj(i -> logEvent("svc", LogSeverity.INFO, "same-template"))
            .toList();
        var result = engineer.extractFeatures("svc", events);
        assertThat(result.getFirst().uniqueTemplateRatio()).isCloseTo(0.05, within(0.01));
    }

    @Test
    void computeFeatures_traceSpanRatio_countsEventsWithTraceId() {
        var events = List.of(
            logEventWithTrace("svc", "trace-1"),
            logEventWithTrace("svc", "trace-2"),
            logEventNoTrace("svc"),
            logEventNoTrace("svc")
        );
        var result = engineer.extractFeatures("svc", events);
        assertThat(result.getFirst().traceSpanRatio()).isCloseTo(0.5, within(0.001));
    }

    @Test
    void computeFeatures_exceptionRate_countsEventsWithExceptionClass() {
        var events = List.of(
            logEventWithException("svc", "NullPointerException"),
            logEventNoException("svc"),
            logEventNoException("svc")
        );
        var result = engineer.extractFeatures("svc", events);
        assertThat(result.getFirst().exceptionRate()).isCloseTo(0.333, within(0.01));
    }

    @Test
    void computeFeatures_eventsUnsorted_areSortedByTimestamp() {
        var now = Instant.now();
        var events = List.of(
            logEventAt("svc", now.plusSeconds(120)),
            logEventAt("svc", now),
            logEventAt("svc", now.plusSeconds(60))
        );
        var result = engineer.extractFeatures("svc", events);
        assertThat(result.getFirst().windowStart()).isBeforeOrEqualTo(result.getFirst().windowEnd());
    }

    @Test
    void extractFeatures_featureVectorHasEightDimensions() {
        var events = generateEventsOverMinutes("svc", 5, 20);
        var vector = engineer.extractFeatures("svc", events).getFirst();
        double[] dims = {vector.errorRate(), vector.uniqueTemplateRatio(), vector.eventRate(),
            vector.p99Latency(), vector.errorBurstScore(), vector.newTemplateRate(),
            vector.traceSpanRatio(), vector.exceptionRate()};
        assertThat(dims).hasSize(8);
        assertThat(Arrays.stream(dims).allMatch(Double::isFinite)).isTrue();
    }
}
```

**`AnomalyDetectorModelTest`** — tests the Smile Isolation Forest train/score/serialize cycle.

```java
class AnomalyDetectorModelTest {

    private AnomalyDetectorModel model;

    @BeforeEach
    void setUp() {
        model = new AnomalyDetectorModel(200, 256);
    }

    @Test
    void score_beforeTraining_throwsIllegalStateException() {
        var vector = normalFeatureVector("svc");
        assertThatThrownBy(() -> model.score(vector))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not trained");
    }

    @Test
    void trainAndScore_normalVector_scoresLow() {
        var trainingData = generateNormalFeatureVectors("svc", 200);
        model.train(trainingData);

        var normalVector = normalFeatureVector("svc");
        assertThat(model.score(normalVector)).isLessThan(0.5);
    }

    @Test
    void trainAndScore_anomalousVector_scoresHigh() {
        var trainingData = generateNormalFeatureVectors("svc", 200);
        model.train(trainingData);

        var anomalous = anomalousFeatureVector("svc"); // errorRate=0.95, exceptionRate=0.9
        assertThat(model.score(anomalous)).isGreaterThan(0.5);
    }

    @Test
    void isAnomalous_respectsThreshold() {
        var trainingData = generateNormalFeatureVectors("svc", 200);
        model.train(trainingData);

        var anomalous = anomalousFeatureVector("svc");
        assertThat(model.isAnomalous(anomalous, 0.3)).isTrue();
        assertThat(model.isAnomalous(normalFeatureVector("svc"), 0.99)).isFalse();
    }

    @Test
    void serializeAndDeserialize_producesConsistentScores() throws Exception {
        var trainingData = generateNormalFeatureVectors("svc", 200);
        model.train(trainingData);

        var testVector = anomalousFeatureVector("svc");
        double originalScore = model.score(testVector);

        byte[] serialized = model.serializeModel();
        var restoredModel = new AnomalyDetectorModel(200, 256);
        restoredModel.loadModel(serialized);

        assertThat(restoredModel.score(testVector)).isCloseTo(originalScore, within(0.0001));
    }
}
```

**`AnomalyEpisodeBuilderTest`** — tests episode grouping, gap merging, and severity classification.

```java
class AnomalyEpisodeBuilderTest {

    private final AnomalyEpisodeBuilder builder = new AnomalyEpisodeBuilder();

    @Test
    void buildEpisodes_noAnomalousWindows_returnsEmpty() {
        var model = trainedModelScoringBelow(0.3); // everything scores < threshold
        var vectors = generateNormalFeatureVectors("svc", 10);

        var episodes = builder.buildEpisodes(UUID.randomUUID(), "svc", vectors, model, 0.65);

        assertThat(episodes).isEmpty();
    }

    @Test
    void buildEpisodes_threeConsecutiveAnomalousWindows_producesSingleEpisode() {
        var model = trainedModelWithAnomalousRange(2, 5); // windows 2-4 anomalous
        var vectors = sequentialFeatureVectors("svc", 10, Duration.ofMinutes(5));

        var episodes = builder.buildEpisodes(UUID.randomUUID(), "svc", vectors, model, 0.65);

        assertThat(episodes).hasSize(1);
        assertThat(episodes.getFirst().windows()).hasSize(3);
    }

    @Test
    void buildEpisodes_fifteenMinuteGap_producesTwoEpisodes() {
        var model = trainedModelWithAnomalousIndices(Set.of(0, 1, 5, 6));
        var vectors = sequentialFeatureVectors("svc", 10, Duration.ofMinutes(5));
        // gap between window 1 end and window 5 start = 15 min > MERGE_GAP (10 min)

        var episodes = builder.buildEpisodes(UUID.randomUUID(), "svc", vectors, model, 0.65);

        assertThat(episodes).hasSize(2);
    }

    @Test
    void buildEpisodes_tenMinuteGap_mergesIntoSingleEpisode() {
        var model = trainedModelWithAnomalousIndices(Set.of(0, 1, 3, 4));
        var vectors = sequentialFeatureVectors("svc", 10, Duration.ofMinutes(5));
        // gap between window 1 end and window 3 start = 5 min < MERGE_GAP (10 min)

        var episodes = builder.buildEpisodes(UUID.randomUUID(), "svc", vectors, model, 0.65);

        assertThat(episodes).hasSize(1);
    }

    @Test
    void classifySeverity_mapsScoreRangesCorrectly() {
        assertThat(classifySeverity(0.55)).isEqualTo(AnomalySeverity.LOW);
        assertThat(classifySeverity(0.7)).isEqualTo(AnomalySeverity.MEDIUM);
        assertThat(classifySeverity(0.85)).isEqualTo(AnomalySeverity.HIGH);
        assertThat(classifySeverity(0.95)).isEqualTo(AnomalySeverity.CRITICAL);
    }

    @Test
    void buildEpisodes_peakScoreReflectsHighestWindowScore() {
        var model = trainedModelWithScores(Map.of(2, 0.7, 3, 0.92, 4, 0.8));
        var vectors = sequentialFeatureVectors("svc", 10, Duration.ofMinutes(5));

        var episodes = builder.buildEpisodes(UUID.randomUUID(), "svc", vectors, model, 0.65);

        assertThat(episodes.getFirst().peakScore()).isCloseTo(0.92, within(0.01));
        assertThat(episodes.getFirst().severity()).isEqualTo(AnomalySeverity.HIGH);
    }
}
```

### Integration Tests

**`AnomalyDetectionServiceIntegrationTest`** — exercises the full pipeline end-to-end using mocked OpenSearch and MinIO.

```java
@SpringBootTest
@Tag("integration")
class AnomalyDetectionServiceIntegrationTest {

    @MockitoBean OpenSearchClientWrapper openSearch;
    @MockitoBean MinioStorageClient minio;
    @Autowired AnomalyDetectionService service;

    @Test
    void detectAnomalies_fullPipeline_producesEpisodesAndStoresArtifacts() {
        var snapshotId = UUID.randomUUID();
        when(openSearch.search(eq("runtime-events"), any()))
            .thenReturn(buildSearchResponse(generateMixedLogEvents("order-service", 500)));

        var result = service.detectAnomalies(snapshotId, List.of("order-service"));

        assertThat(result.totalEpisodes()).isGreaterThanOrEqualTo(0);
        verify(minio).putBytes(eq("models"), contains("order-service.smile"), any(byte[].class));
        verify(minio).putJson(eq("evidence"), contains(snapshotId.toString()), any());
    }

    @Test
    void detectAnomalies_multipleServices_trainsPerServiceModels() {
        var snapshotId = UUID.randomUUID();
        var services = List.of("order-service", "payment-service", "shipping-service");
        for (var svc : services) {
            when(openSearch.search(eq("runtime-events"), argThat(q -> q.toString().contains(svc))))
                .thenReturn(buildSearchResponse(generateNormalLogEvents(svc, 200)));
        }

        service.detectAnomalies(snapshotId, services);

        verify(minio, times(3)).putBytes(eq("models"), contains(".smile"), any(byte[].class));
    }
}
```

### Test Fixtures & Sample Data

| Fixture file | Description |
|---|---|
| `src/test/resources/fixtures/log-events-normal.json` | 200 normal log events for a single service — low error rate, consistent templates |
| `src/test/resources/fixtures/log-events-anomalous.json` | 50 events with high error rate, burst errors, novel templates — triggers anomaly detection |
| `src/test/resources/fixtures/log-events-mixed.json` | 500 events with a 10-minute anomalous window embedded in normal traffic |
| `src/test/resources/fixtures/feature-vectors-training.json` | 200 pre-computed feature vectors for model training — avoids recomputing in model tests |

Sample data guidelines:
- Normal log events: errorRate < 0.05, 10–15 distinct templates, events with and without trace IDs.
- Anomalous log events: errorRate > 0.8, exceptionRate > 0.5, error bursts of 5+ consecutive events.
- Each `ParsedLogEvent` must include `timestamp`, `severity`, `templateId`, `serviceName`, and optionally `traceId` and `exceptionClass`.

### Mocking Strategy

| Dependency | Unit tests | Integration tests |
|---|---|---|
| `LogFeatureEngineer` | Real instance (pure computation, no I/O) | Real instance |
| `AnomalyDetectorModel` | Real Smile instance for train/score tests; mock for episode builder tests (control scores) | Real instance |
| `AnomalyEpisodeBuilder` | Real instance (pure logic) | Real instance |
| `OpenSearchClientWrapper` | Not used in unit tests | `@MockitoBean` — return fixture data |
| `MinioStorageClient` | Not used in unit tests | `@MockitoBean` — verify `putBytes`/`putJson` calls |
| `MeterRegistry` | `SimpleMeterRegistry` or mock | `SimpleMeterRegistry` |

- `LogFeatureEngineer` and `AnomalyEpisodeBuilder` are pure computational components — always use real instances, never mock.
- For `AnomalyDetectorModel` in episode builder tests, create a stub that returns controlled scores for specific vectors to test grouping and severity logic deterministically.

### CI/CD Considerations

- **JUnit tags:** Unit tests untagged. Integration tests tagged `@Tag("integration")`.
- **Gradle filtering:**
  ```kotlin
  tasks.test { useJUnitPlatform { excludeTags("integration") } }
  tasks.register<Test>("integrationTest") {
      useJUnitPlatform { includeTags("integration") }
  }
  ```
- **No Docker needed:** This stage's integration tests mock external stores (OpenSearch, MinIO). Unit tests use Smile directly. No Testcontainers required.
- **Smile dependency:** Smile is published on Maven Central (`com.github.haifengl:smile-core`) — no special repository configuration needed. It is pure Java with no native dependencies.
- **Model reproducibility:** Smile's `IsolationForest.fit()` uses random sampling internally. For deterministic tests, use a sufficiently large training set (200+ vectors) and verify score ordering rather than exact values.
- **Test duration:** Smile model training with 200 vectors and 200 trees completes in ~1 second. No special timeout configuration needed.

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| Feature extraction | Feed 1000 log events | Feature vectors with 8 features per window |
| Window boundaries | 30 min of logs, 5-min windows | 6 windows created |
| Error rate | Window with 50% errors | errorRate = 0.5 |
| Train model | Train on 100 feature vectors | No exception, model serializable |
| Score normal | Score a normal window | Score < 0.5 |
| Score anomalous | Score high-error-rate window | Score > 0.65 |
| Episode grouping | 3 consecutive anomalous windows | 1 episode |
| Episode gap | 2 anomalous windows, 15-min gap | 2 separate episodes |
| Severity mapping | Peak score 0.9 | CRITICAL severity |
| Model serialization | Serialize + deserialize | Same scores after round-trip |
| OpenSearch index | Run detection | Episodes in anomaly-episodes |
| MinIO model | Run detection | .smile model file in models bucket |

## Files to create

- `libs/anomaly/build.gradle.kts`
- `libs/anomaly/src/main/java/com/flowforge/anomaly/feature/LogFeatureEngineer.java`
- `libs/anomaly/src/main/java/com/flowforge/anomaly/model/AnomalyDetectorModel.java`
- `libs/anomaly/src/main/java/com/flowforge/anomaly/episode/AnomalyEpisodeBuilder.java`
- `libs/anomaly/src/main/java/com/flowforge/anomaly/service/AnomalyDetectionService.java`
- `libs/anomaly/src/test/java/.../LogFeatureEngineerTest.java`
- `libs/anomaly/src/test/java/.../AnomalyDetectorModelTest.java`
- `libs/anomaly/src/test/java/.../AnomalyEpisodeBuilderTest.java`
- `libs/anomaly/src/test/java/.../AnomalyDetectionServiceIntegrationTest.java`

## Depends on

- Stage 09 (parsed log events)
- Stage 07 (OpenSearch anomaly-episodes index)

## Produces

- Per-service Smile anomaly models in MinIO
- Anomaly episodes in OpenSearch `anomaly-episodes` index
- Anomaly report in MinIO evidence bucket
- Foundation for Neo4j HAS_ANOMALY relationships
