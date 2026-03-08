# Stage 16 — Log Embedding Pipeline (Spring AI + TEI)

## Goal

Embed parsed log events into dense vectors using **Spring AI EmbeddingModel** backed by **TEI** serving **E5-large-v2** (1024-dim). Build a batch pipeline that transforms log templates and events into embedding-friendly text, embeds them, and stores in the Qdrant `log-embeddings` collection.

## Prerequisites

- Stage 09 (parsed log events)
- Stage 14 (Qdrant vector store)
- Stage 15 (embedding configuration)

## What to build

### 16.1 TEI deployment (E5-large-v2)

For local dev, use `docker/docker-compose.yml` with GPU passthrough, or run TEI in CPU mode with `--dtype float32`.

**ArgoCD Application** — registered in the App-of-Apps root:

```yaml
# k8s/argocd/apps/tei-log.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: flowforge-tei-log
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "4"
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: flowforge
  source:
    repoURL: https://github.com/tesco/flow-forge.git
    targetRevision: HEAD
    path: k8s/ml-serving/tei-log
  destination:
    server: https://kubernetes.default.svc
    namespace: flowforge-ml
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

**Deployment** — GPU-scheduled TEI serving E5-large-v2:

```yaml
# k8s/ml-serving/tei-log/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: tei-log
  namespace: flowforge-ml
  labels:
    app.kubernetes.io/name: tei-log
    app.kubernetes.io/component: embedding
    app.kubernetes.io/part-of: flowforge
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: tei-log
  template:
    metadata:
      labels:
        app.kubernetes.io/name: tei-log
        app.kubernetes.io/component: embedding
    spec:
      nodeSelector:
        agentpool: gpupool
      tolerations:
        - key: nvidia.com/gpu
          operator: Equal
          value: present
          effect: NoSchedule
      containers:
        - name: tei-log
          image: ghcr.io/huggingface/text-embeddings-inference:1.5
          args:
            - --model-id
            - intfloat/e5-large-v2
            - --port
            - "8082"
          ports:
            - containerPort: 8082
              name: http
              protocol: TCP
          resources:
            requests:
              cpu: "2"
              memory: 16Gi
              nvidia.com/gpu: "1"
            limits:
              cpu: "2"
              memory: 16Gi
              nvidia.com/gpu: "1"
          readinessProbe:
            httpGet:
              path: /health
              port: 8082
            initialDelaySeconds: 30
            periodSeconds: 10
          volumeMounts:
            - name: model-cache
              mountPath: /data
      volumes:
        - name: model-cache
          persistentVolumeClaim:
            claimName: tei-log-model-cache
```

**Service** — cluster-internal access on port 8082:

```yaml
# k8s/ml-serving/tei-log/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: tei-log
  namespace: flowforge-ml
  labels:
    app.kubernetes.io/name: tei-log
spec:
  type: ClusterIP
  selector:
    app.kubernetes.io/name: tei-log
  ports:
    - port: 8082
      targetPort: 8082
      protocol: TCP
      name: http
```

### 16.2 Log text builder

```java
@Component
public class LogEmbeddingTextBuilder {

    /**
     * Build embedding-friendly text from a log event.
     * E5-large-v2 expects "query:" or "passage:" prefixed text.
     *
     * For indexing (passage):
     *   "passage: [booking-service] ERROR: Failed to connect to <*> on port <*> | exception: ConnectionRefusedException"
     *
     * For searching (query):
     *   "query: connection failure timeout database"
     */
    public String buildPassageText(ParsedLogEvent event) {
        var sb = new StringBuilder("passage: ");

        // Service context
        sb.append("[").append(event.serviceName()).append("] ");

        // Severity
        sb.append(event.severity().name()).append(": ");

        // Template (not raw message — templates are generalized)
        sb.append(event.template());

        // Exception info if present
        event.exceptionClass().ifPresent(exc -> {
            sb.append(" | exception: ").append(exc);
            event.exceptionMessage().ifPresent(msg ->
                sb.append(" - ").append(truncate(msg, 200)));
        });

        // Logger context
        event.loggerName().ifPresent(logger ->
            sb.append(" | logger: ").append(logger));

        return sb.toString();
    }

    /**
     * Build query text for searching log embeddings.
     */
    public String buildQueryText(String query) {
        return "query: " + query;
    }

    /**
     * Build text from a Drain cluster template for embedding.
     */
    public String buildTemplateText(String serviceName, DrainParser.LogCluster cluster) {
        return "passage: [%s] Log template: %s (frequency: %d)"
            .formatted(serviceName, cluster.templateString(), cluster.matchCount().get());
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
```

### 16.3 Log embedding service

```java
@Service
public class LogEmbeddingService {

    private static final int BATCH_SIZE = 128;     // Logs are shorter than code, larger batches
    private static final int MAX_EVENTS = 50_000;  // Cap per snapshot to control cost

    private final EmbeddingModel logEmbeddingModel;
    private final VectorStoreService vectorStoreService;
    private final LogEmbeddingTextBuilder textBuilder;
    private final OpenSearchClientWrapper openSearch;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    /**
     * Embed log events for a snapshot, using template deduplication.
     *
     * Strategy: Embed unique templates (Drain clusters) + sample of raw events.
     * This avoids embedding millions of near-identical log lines.
     */
    public LogEmbeddingResult embedSnapshot(UUID snapshotId) {
        // 1. Fetch unique templates from MinIO evidence
        var clusters = fetchDrainClusters(snapshotId);
        log.info("Found {} unique log templates for snapshot {}", clusters.size(), snapshotId);

        // 2. Embed templates (always — there are far fewer templates than events)
        var templateDocs = clusters.entrySet().stream()
            .map(entry -> buildTemplateDocument(snapshotId, entry.getKey(), entry.getValue()))
            .toList();

        // 3. Embed a sample of raw events (for high-fidelity search)
        var eventDocs = fetchAndSampleEvents(snapshotId, MAX_EVENTS).stream()
            .map(event -> buildEventDocument(snapshotId, event))
            .toList();

        // 4. Combine and batch-embed
        var allDocs = new ArrayList<>(templateDocs);
        allDocs.addAll(eventDocs);

        int embedded = 0;
        for (var batch : partition(allDocs, BATCH_SIZE)) {
            meterRegistry.timer("flowforge.embedding.log.batch").record(() ->
                vectorStoreService.addLogDocuments(batch)
            );
            embedded += batch.size();
        }

        // 5. Store stats
        var stats = new LogEmbeddingStats(snapshotId, templateDocs.size(),
            eventDocs.size(), 1024, "intfloat/e5-large-v2");
        minio.putJson("evidence", "embeddings/log/" + snapshotId + ".json", stats);

        meterRegistry.counter("flowforge.embedding.log.total").increment(allDocs.size());

        return new LogEmbeddingResult(templateDocs.size(), eventDocs.size(), 1024);
    }

    private Document buildTemplateDocument(UUID snapshotId, String serviceName,
                                            DrainParser.LogCluster cluster) {
        var content = textBuilder.buildTemplateText(serviceName, cluster);
        var metadata = Map.<String, Object>of(
            "snapshot_id", snapshotId.toString(),
            "service_name", serviceName,
            "type", "template",
            "template_id", cluster.clusterId(),
            "match_count", cluster.matchCount().get()
        );
        return new Document(content, metadata);
    }

    private Document buildEventDocument(UUID snapshotId, ParsedLogEvent event) {
        var content = textBuilder.buildPassageText(event);
        var metadata = new HashMap<String, Object>();
        metadata.put("snapshot_id", snapshotId.toString());
        metadata.put("service_name", event.serviceName());
        metadata.put("type", "event");
        metadata.put("severity", event.severity().name());
        metadata.put("template_id", event.templateId());
        event.traceId().ifPresent(t -> metadata.put("trace_id", t));
        event.exceptionClass().ifPresent(e -> metadata.put("exception_class", e));
        return new Document(content, metadata);
    }

    /**
     * Stratified sampling: keep all ERROR/FATAL events, sample INFO/DEBUG.
     */
    private List<ParsedLogEvent> fetchAndSampleEvents(UUID snapshotId, int maxEvents) {
        var allEvents = fetchLogEvents(snapshotId);

        // Always keep error events
        var errors = allEvents.stream()
            .filter(e -> e.severity() == ParsedLogEvent.LogSeverity.ERROR
                      || e.severity() == ParsedLogEvent.LogSeverity.FATAL)
            .toList();

        var remaining = maxEvents - errors.size();
        if (remaining <= 0) return errors.subList(0, Math.min(errors.size(), maxEvents));

        // Sample from non-error events
        var nonErrors = allEvents.stream()
            .filter(e -> e.severity() != ParsedLogEvent.LogSeverity.ERROR
                      && e.severity() != ParsedLogEvent.LogSeverity.FATAL)
            .toList();

        var sampled = new ArrayList<ParsedLogEvent>(errors);
        if (nonErrors.size() <= remaining) {
            sampled.addAll(nonErrors);
        } else {
            // Stratified sample across services
            var byService = nonErrors.stream()
                .collect(Collectors.groupingBy(ParsedLogEvent::serviceName));
            int perService = remaining / Math.max(byService.size(), 1);
            byService.values().forEach(events ->
                sampled.addAll(events.subList(0, Math.min(events.size(), perService)))
            );
        }

        return sampled;
    }

    private Map<String, DrainParser.LogCluster> fetchDrainClusters(UUID snapshotId) {
        var key = "parsed-logs/%s/drain-clusters.json".formatted(snapshotId);
        return minioClient.getObject("parsed-logs", key, new TypeReference<Map<String, DrainParser.LogCluster>>() {});
    }

    private List<ParsedLogEvent> fetchLogEvents(UUID snapshotId) {
        var query = new SearchRequest.Builder()
            .index("runtime-events")
            .query(q -> q.bool(b -> b
                .must(m -> m.term(t -> t.field("snapshot_id").value(snapshotId.toString())))))
            .size(10_000)
            .build();
        return openSearch.search(query, ParsedLogEvent.class);
    }

    private <T> List<List<T>> partition(List<T> list, int batchSize) {
        var partitions = new ArrayList<List<T>>();
        for (int i = 0; i < list.size(); i += batchSize) {
            partitions.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return partitions;
    }
}

public record LogEmbeddingResult(int templatesEmbedded, int eventsEmbedded, int dimensions) {}
public record LogEmbeddingStats(UUID snapshotId, int templates, int events, int dimensions, String model) {}
```

### 16.4 Dependencies

Same as Stage 15 — uses `libs/embedding` module.

```kotlin
// libs/embedding/build.gradle.kts (additions for log embedding)
dependencies {
    implementation(project(":libs:log-parser"))
}
```

## Testing & Verification Strategy

### Unit Tests

**`LogEmbeddingTextBuilderTest`** — pure-function tests with no Spring context needed.

```java
class LogEmbeddingTextBuilderTest {

    private final LogEmbeddingTextBuilder builder = new LogEmbeddingTextBuilder();

    @Test
    void passageText_startsWithPassagePrefix() {
        var event = TestFixtures.parsedLogEvent("booking-service", ParsedLogEvent.LogSeverity.ERROR,
            "Failed to connect to <*> on port <*>");
        var text = builder.buildPassageText(event);
        assertThat(text).startsWith("passage: ");
    }

    @Test
    void passageText_containsServiceNameInBrackets() {
        var event = TestFixtures.parsedLogEvent("payment-service", ParsedLogEvent.LogSeverity.INFO,
            "Processing payment");
        var text = builder.buildPassageText(event);
        assertThat(text).contains("[payment-service]");
    }

    @Test
    void passageText_includesSeverityAndTemplate() {
        var event = TestFixtures.parsedLogEvent("booking-service", ParsedLogEvent.LogSeverity.ERROR,
            "Connection refused to host <*>");
        var text = builder.buildPassageText(event);
        assertThat(text).contains("ERROR: Connection refused to host <*>");
    }

    @Test
    void passageText_appendsExceptionInfoWhenPresent() {
        var event = TestFixtures.parsedLogEventWithException("order-service",
            ParsedLogEvent.LogSeverity.ERROR, "Request failed",
            "ConnectionRefusedException", "Connection timed out after 5000ms");
        var text = builder.buildPassageText(event);
        assertThat(text)
            .contains("| exception: ConnectionRefusedException")
            .contains("- Connection timed out after 5000ms");
    }

    @Test
    void passageText_truncatesLongExceptionMessages() {
        var longMsg = "X".repeat(500);
        var event = TestFixtures.parsedLogEventWithException("svc", ParsedLogEvent.LogSeverity.ERROR,
            "Boom", "RuntimeException", longMsg);
        var text = builder.buildPassageText(event);
        assertThat(text).hasSizeLessThan(500);
        assertThat(text).contains("...");
    }

    @Test
    void queryText_startsWithQueryPrefix() {
        var text = builder.buildQueryText("connection timeout database");
        assertThat(text).isEqualTo("query: connection timeout database");
    }

    @Test
    void templateText_containsServiceAndFrequency() {
        var cluster = TestFixtures.drainCluster("CL-001", "Failed to connect to <*>", 42);
        var text = builder.buildTemplateText("booking-service", cluster);
        assertThat(text)
            .startsWith("passage: ")
            .contains("[booking-service]")
            .contains("Log template:")
            .contains("(frequency: 42)");
    }
}
```

**`LogEmbeddingServiceTest`** — mock collaborators, test orchestration and sampling logic.

```java
@ExtendWith(MockitoExtension.class)
class LogEmbeddingServiceTest {

    @Mock EmbeddingModel logEmbeddingModel;
    @Mock VectorStoreService vectorStoreService;
    @Mock LogEmbeddingTextBuilder textBuilder;
    @Mock OpenSearchClientWrapper openSearch;
    @Mock MinioStorageClient minio;
    @Mock MeterRegistry meterRegistry;
    @Mock Timer timer;
    @Mock Counter counter;

    @InjectMocks LogEmbeddingService service;

    @BeforeEach
    void stubMetrics() {
        when(meterRegistry.timer(anyString())).thenReturn(timer);
        when(timer.record(any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run(); return null;
        });
        when(meterRegistry.counter(anyString())).thenReturn(counter);
    }

    @Test
    void stratifiedSampling_keepsAllErrorEvents() {
        var errors = TestFixtures.logEvents(200, ParsedLogEvent.LogSeverity.ERROR);
        var infos = TestFixtures.logEvents(60_000, ParsedLogEvent.LogSeverity.INFO);
        var all = new ArrayList<>(errors);
        all.addAll(infos);

        var sampled = invokeStratifiedSample(all, 50_000);

        long errorCount = sampled.stream()
            .filter(e -> e.severity() == ParsedLogEvent.LogSeverity.ERROR).count();
        assertThat(errorCount).isEqualTo(200);
        assertThat(sampled).hasSizeLessThanOrEqualTo(50_000);
    }

    @Test
    void stratifiedSampling_samplesAcrossServices() {
        var svcA = TestFixtures.logEvents("svc-a", 30_000, ParsedLogEvent.LogSeverity.INFO);
        var svcB = TestFixtures.logEvents("svc-b", 30_000, ParsedLogEvent.LogSeverity.INFO);
        var all = new ArrayList<>(svcA);
        all.addAll(svcB);

        var sampled = invokeStratifiedSample(all, 10_000);

        var bySvc = sampled.stream().collect(Collectors.groupingBy(ParsedLogEvent::serviceName));
        assertThat(bySvc.get("svc-a")).hasSizeGreaterThan(0);
        assertThat(bySvc.get("svc-b")).hasSizeGreaterThan(0);
    }

    @Test
    void embedSnapshot_storesStatsInMinio() {
        // ...setup clusters and events...
        service.embedSnapshot(UUID.randomUUID());
        verify(minio).putJson(eq("evidence"), contains("embeddings/log/"), any());
    }

    @Test
    void embedSnapshot_batchesByConfiguredSize() {
        // Provide 300 documents, expect 3 batches of 128, 128, 44
        // ...setup...
        service.embedSnapshot(UUID.randomUUID());
        verify(vectorStoreService, times(3)).addLogDocuments(anyList());
    }
}
```

### Integration Tests

**`LogEmbeddingServiceIntegrationTest`** — uses WireMock for TEI and Testcontainers for Qdrant.

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("integration")
class LogEmbeddingServiceIntegrationTest {

    @Container
    static QdrantContainer qdrant = new QdrantContainer("qdrant/qdrant:v1.12.0");

    @RegisterExtension
    static WireMockExtension teiMock = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.vectorstore.qdrant.host", qdrant::getHost);
        registry.add("spring.ai.vectorstore.qdrant.port", () -> qdrant.getGrpcPort());
        registry.add("flowforge.tei.log-url", teiMock::baseUrl);
    }

    @Autowired LogEmbeddingService logEmbeddingService;

    @BeforeEach
    void stubTei() {
        teiMock.stubFor(post(urlPathEqualTo("/v1/embeddings"))
            .willReturn(okJson(TestFixtures.teiEmbeddingResponse(1024))));
    }

    @Test
    void fullSnapshotEmbedding_storesVectorsInQdrant() {
        var result = logEmbeddingService.embedSnapshot(TestFixtures.SNAPSHOT_ID);
        assertThat(result.templatesEmbedded()).isGreaterThan(0);
        assertThat(result.eventsEmbedded()).isGreaterThan(0);
        assertThat(result.dimensions()).isEqualTo(1024);
    }

    @Test
    void embeddedDocuments_areSearchableByQuery() {
        logEmbeddingService.embedSnapshot(TestFixtures.SNAPSHOT_ID);
        // Search via the vector store
        var results = vectorStoreService.searchLogs("query: connection timeout", 5,
            TestFixtures.SNAPSHOT_ID.toString(), Optional.empty());
        assertThat(results).isNotEmpty();
    }
}
```

### Test Fixtures & Sample Data

| Fixture file | Description |
|---|---|
| `src/test/resources/fixtures/drain-clusters.json` | 10 Drain log clusters with template strings, cluster IDs, match counts across 3 services |
| `src/test/resources/fixtures/parsed-log-events.json` | 500 parsed log events: 50 ERROR/FATAL, 450 INFO/DEBUG across 3 services |
| `src/test/resources/fixtures/tei-embedding-response.json` | Sample TEI `/embed` response with 1024-dim float arrays |
| `TestFixtures.java` | Factory methods: `parsedLogEvent(...)`, `parsedLogEventWithException(...)`, `drainCluster(...)`, `logEvents(count, severity)`, `logEvents(service, count, severity)` |

### Mocking Strategy

| Dependency | Mock or Real | Rationale |
|---|---|---|
| `EmbeddingModel` | **Mock** in unit tests | Avoid TEI dependency; verify call args |
| `VectorStoreService` | **Mock** in unit tests, **real** in integration | Verify batch add calls; integration uses Qdrant testcontainer |
| `OpenSearchClientWrapper` | **Mock** | Return canned parsed log events |
| `MinioStorageClient` | **Mock** in unit tests, **real** in integration | Verify evidence storage path |
| `MeterRegistry` | **Mock** (SimpleMeterRegistry) | Lightweight metric stubs |
| TEI HTTP API | **WireMock** in integration tests | Deterministic embedding responses |

### CI/CD Considerations

- **Test tags**: `@Tag("unit")` for builder/sampling tests, `@Tag("integration")` for WireMock + Qdrant tests.
- **Docker images required**: `qdrant/qdrant:v1.12.0` for Qdrant Testcontainer.
- **Gradle filtering**: `./gradlew :libs:embedding:test --tests '*' -PincludeTags=unit` for fast CI; integration tests in a separate stage with Docker socket access.
- **CI parallelism**: Unit tests are fully parallelizable. Integration tests sharing the Qdrant container should run sequentially within the module.
- **Testcontainers reuse**: Enable `testcontainers.reuse.enable=true` in `~/.testcontainers.properties` for local dev speed.

## Verification

**Stage 16 sign-off requires all stages 1 through 16 to pass.** Run: `make verify`.

The verification report for stage 16 is `logs/stage-16.log`. It contains **cumulative output for stages 1–16** (Stage 1, then Stage 2, … then Stage 16 output).

| Check | How to verify | Pass criteria |
|---|---|---|
| TEI pod running | `kubectl get pods -n flowforge-ml -l app.kubernetes.io/name=tei-log` | Pod STATUS Running, 1/1 Ready |
| ArgoCD synced | `argocd app get flowforge-tei-log` | Sync status Healthy / Synced |
| Template embed | Embed 1 Drain template | 1024-dim vector in Qdrant |
| Event embed | Embed 1 log event | 1024-dim vector in Qdrant |
| E5 prefix | Check passage text | Starts with "passage:" |
| Query prefix | Build query text | Starts with "query:" |
| Batch embed | Embed 128 log events | All stored in log-embeddings |
| Full snapshot | Embed all templates + samples | Counts match expected |
| Stratified sample | 100K events, max 50K | All errors kept, info/debug sampled |
| Service context | Check embedded text | Contains `[booking-service]` |
| Exception info | ERROR with exception | Includes exception class |
| Template dedup | 100 events → 5 templates | 5 template embeddings + event samples |
| Search | Query "connection timeout" | Returns relevant log events |
| Metadata filter | Search per-service | Only matching service |
| Evidence | Check MinIO | Embedding stats JSON present |

## Files to create

- `k8s/argocd/apps/tei-log.yaml`
- `k8s/ml-serving/tei-log/kustomization.yaml`
- `k8s/ml-serving/tei-log/deployment.yaml`
- `k8s/ml-serving/tei-log/service.yaml`
- `k8s/ml-serving/tei-log/pvc.yaml`
- `libs/embedding/src/main/java/com/flowforge/embedding/text/LogEmbeddingTextBuilder.java`
- `libs/embedding/src/main/java/com/flowforge/embedding/service/LogEmbeddingService.java`
- `libs/embedding/src/test/java/.../LogEmbeddingTextBuilderTest.java`
- `libs/embedding/src/test/java/.../LogEmbeddingServiceIntegrationTest.java` (WireMock for TEI)

## Depends on

- Stage 09 (parsed log events + Drain clusters)
- Stage 14 (Qdrant log-embeddings collection)
- Stage 15 (EmbeddingModel configuration)

## Produces

- Log template embeddings (1024-dim, E5-large-v2) in Qdrant `log-embeddings`
- Stratified log event sample embeddings in Qdrant `log-embeddings`
- Log embedding stats in MinIO evidence bucket
