# Stage 15 — Code Embedding Pipeline (Spring AI + TEI)

## Goal

Embed all code chunks into dense vectors using **Spring AI EmbeddingModel** backed by **Text Embeddings Inference (TEI)** serving **CodeSage-large** (1024-dim). Build a batch pipeline that embeds parsed code chunks and stores them in the Qdrant code-embeddings collection via Spring AI VectorStore.

## Prerequisites

- Stage 08 (parsed code chunks)
- Stage 14 (Qdrant vector store)

## What to build

### 15.1 TEI deployment (CodeSage-large)

For local dev, use `docker/docker-compose.yml` with GPU passthrough, or run TEI in CPU mode with `--dtype float32`.

**ArgoCD Application** — registered in the App-of-Apps root:

```yaml
# k8s/argocd/apps/tei-code.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: flowforge-tei-code
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
    path: k8s/ml-serving/tei-code
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

**Deployment** — GPU-scheduled TEI serving CodeSage-large:

```yaml
# k8s/ml-serving/tei-code/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: tei-code
  namespace: flowforge-ml
  labels:
    app.kubernetes.io/name: tei-code
    app.kubernetes.io/component: embedding
    app.kubernetes.io/part-of: flowforge
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: tei-code
  template:
    metadata:
      labels:
        app.kubernetes.io/name: tei-code
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
        - name: tei-code
          image: ghcr.io/huggingface/text-embeddings-inference:1.5
          args:
            - --model-id
            - codesage/codesage-large
            - --port
            - "8081"
          ports:
            - containerPort: 8081
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
              port: 8081
            initialDelaySeconds: 30
            periodSeconds: 10
          volumeMounts:
            - name: model-cache
              mountPath: /data
      volumes:
        - name: model-cache
          persistentVolumeClaim:
            claimName: tei-code-model-cache
```

**Service** — cluster-internal access on port 8081:

```yaml
# k8s/ml-serving/tei-code/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: tei-code
  namespace: flowforge-ml
  labels:
    app.kubernetes.io/name: tei-code
spec:
  type: ClusterIP
  selector:
    app.kubernetes.io/name: tei-code
  ports:
    - port: 8081
      targetPort: 8081
      protocol: TCP
      name: http
```

### 15.2 Spring AI EmbeddingModel configuration

```java
@Configuration
public class EmbeddingConfig {

    /**
     * Code embedding model — connects to TEI serving CodeSage-large.
     * TEI exposes an OpenAI-compatible /v1/embeddings endpoint.
     */
    @Bean("codeEmbeddingModel")
    public EmbeddingModel codeEmbeddingModel(FlowForgeProperties props) {
        var options = OpenAiEmbeddingOptions.builder()
            .model("codesage/codesage-large")
            .dimensions(1024)
            .build();

        return new OpenAiEmbeddingModel(
            OpenAiApi.builder()
                .baseUrl(props.tei().codeUrl())     // http://tei-code:8081
                .apiKey("not-needed")                // TEI doesn't require API key
                .build(),
            options
        );
    }

    /**
     * Log embedding model — connects to TEI serving E5-large-v2.
     * Configured in Stage 16 but declared here for reference.
     */
    @Bean("logEmbeddingModel")
    public EmbeddingModel logEmbeddingModel(FlowForgeProperties props) {
        var options = OpenAiEmbeddingOptions.builder()
            .model("intfloat/e5-large-v2")
            .dimensions(1024)
            .build();

        return new OpenAiEmbeddingModel(
            OpenAiApi.builder()
                .baseUrl(props.tei().logUrl())      // http://tei-log:8082
                .apiKey("not-needed")
                .build(),
            options
        );
    }
}
```

### 15.3 Code embedding service

```java
@Service
public class CodeEmbeddingService {

    private static final int BATCH_SIZE = 64;

    private final EmbeddingModel codeEmbeddingModel;
    private final VectorStoreService vectorStoreService;
    private final OpenSearchClientWrapper openSearch;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    /**
     * Embed all code chunks for a snapshot and store in Qdrant.
     */
    public CodeEmbeddingResult embedSnapshot(UUID snapshotId) {
        // 1. Fetch code chunks from OpenSearch
        var chunks = fetchCodeChunks(snapshotId);
        log.info("Embedding {} code chunks for snapshot {}", chunks.size(), snapshotId);

        // 2. Convert to Spring AI Documents
        var documents = chunks.stream()
            .map(chunk -> buildDocument(snapshotId, chunk))
            .toList();

        // 3. Batch embed and store
        int embedded = 0;
        for (var batch : partition(documents, BATCH_SIZE)) {
            meterRegistry.timer("flowforge.embedding.code.batch").record(() ->
                vectorStoreService.addCodeDocuments(batch)
            );
            embedded += batch.size();
            log.debug("Embedded {}/{} code chunks", embedded, documents.size());
        }

        // 4. Store embedding stats as evidence
        var stats = new EmbeddingStats(snapshotId, documents.size(), 1024, "codesage/codesage-large");
        minio.putJson("evidence", "embeddings/code/" + snapshotId + ".json", stats);

        meterRegistry.counter("flowforge.embedding.code.total").increment(documents.size());

        return new CodeEmbeddingResult(documents.size(), 1024);
    }

    private Document buildDocument(UUID snapshotId, Map<String, Object> chunk) {
        var content = buildEmbeddingText(chunk);

        var metadata = new HashMap<String, Object>();
        metadata.put("snapshot_id", snapshotId.toString());
        metadata.put("service_name", chunk.get("service_name"));
        metadata.put("class_fqn", chunk.get("class_fqn"));
        metadata.put("method_name", chunk.getOrDefault("method_name", ""));
        metadata.put("chunk_type", chunk.get("chunk_type"));
        metadata.put("file_path", chunk.get("file_path"));
        metadata.put("reactive_complexity", chunk.getOrDefault("reactive_complexity", "NONE"));
        metadata.put("annotations", chunk.getOrDefault("annotations", List.of()));
        metadata.put("line_start", chunk.getOrDefault("line_start", 0));
        metadata.put("line_end", chunk.getOrDefault("line_end", 0));
        metadata.put("content_hash", chunk.get("content_hash"));

        return new Document(content, metadata);
    }

    /**
     * Build the text that will be embedded.
     * Prepend class/method context for better semantic retrieval.
     */
    private String buildEmbeddingText(Map<String, Object> chunk) {
        var sb = new StringBuilder();

        // Add context prefix
        var classFqn = (String) chunk.get("class_fqn");
        var methodName = (String) chunk.getOrDefault("method_name", "");
        var chunkType = (String) chunk.get("chunk_type");
        var annotations = chunk.getOrDefault("annotations", List.of());

        sb.append("// ").append(chunkType).append(": ").append(classFqn);
        if (!methodName.isEmpty()) {
            sb.append(".").append(methodName);
        }
        sb.append("\n");

        if (annotations instanceof List<?> annots && !annots.isEmpty()) {
            sb.append("// Annotations: ").append(String.join(", ",
                annots.stream().map(Object::toString).toList())).append("\n");
        }

        sb.append(chunk.get("content"));
        return sb.toString();
    }

    /**
     * Fetch all code chunks using search_after pagination to handle
     * snapshots with more than 10K chunks (OpenSearch default limit).
     */
    private List<Map<String, Object>> fetchCodeChunks(UUID snapshotId) {
        var allChunks = new ArrayList<Map<String, Object>>();
        int pageSize = 5000;
        Object[] searchAfter = null;

        while (true) {
            var query = new HashMap<String, Object>();
            query.put("query", Map.of("term", Map.of("snapshot_id", snapshotId.toString())));
            query.put("size", pageSize);
            query.put("sort", List.of(Map.of("_doc", "asc")));
            if (searchAfter != null) {
                query.put("search_after", searchAfter);
            }

            var hits = openSearch.search("code-artifacts", query).getHits();
            if (hits.isEmpty()) break;

            hits.forEach(hit -> allChunks.add(hit.getSourceAsMap()));
            var lastHit = hits.get(hits.size() - 1);
            searchAfter = lastHit.getSortValues();
        }
        return allChunks;
    }

    /** Partition a list into batches. */
    private <T> List<List<T>> partition(List<T> list, int batchSize) {
        var batches = new ArrayList<List<T>>();
        for (int i = 0; i < list.size(); i += batchSize) {
            batches.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return batches;
    }
}

public record CodeEmbeddingResult(int chunksEmbedded, int dimensions) {}
public record EmbeddingStats(UUID snapshotId, int documentCount, int dimensions, String model) {}
```

### 15.4 Embedding health indicator

```java
@Component
public class TeiHealthIndicator implements HealthIndicator {

    private final RestClient restClient;
    private final FlowForgeProperties props;

    @Override
    public Health health() {
        try {
            var response = restClient.get()
                .uri(props.tei().codeUrl() + "/health")
                .retrieve()
                .body(String.class);
            return Health.up()
                .withDetail("model", "codesage/codesage-large")
                .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
```

### 15.5 Dependencies

```kotlin
// libs/embedding/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:vector-store"))
    implementation(libs.spring.ai.openai)         // Spring AI OpenAI (TEI is OpenAI-compatible)
}
```

> **Note:** The Spring AI OpenAI dependency is already defined in the version catalog (Stage 01) as:
> ```toml
> spring-ai-openai-spring-boot-starter = { module = "org.springframework.ai:spring-ai-openai-spring-boot-starter", version.ref = "spring-ai" }
> ```
> Use `libs.spring.ai.openai.spring.boot.starter` in Gradle. The starter auto-configures `ChatModel`
> and `EmbeddingModel` beans. For non-Boot modules that only need the API classes, use
> `org.springframework.ai:spring-ai-openai` directly.

### 15.6 Application configuration

```yaml
# application.yml
flowforge:
  tei:
    code-url: http://localhost:8081
    log-url: http://localhost:8082
    reranker-url: http://localhost:8083
```

## Testing & Verification Strategy

### Unit Tests

**`CodeEmbeddingServiceTest`** — tests document building, embedding text construction, pagination, and batching with mocked dependencies.

```java
@ExtendWith(MockitoExtension.class)
class CodeEmbeddingServiceTest {

    @Mock @Qualifier("codeEmbeddingModel") EmbeddingModel codeEmbeddingModel;
    @Mock VectorStoreService vectorStoreService;
    @Mock OpenSearchClientWrapper openSearch;
    @Mock MinioStorageClient minio;
    @Mock MeterRegistry meterRegistry;
    @Mock Timer timer;

    @InjectMocks CodeEmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        when(meterRegistry.timer(anyString())).thenReturn(timer);
        when(timer.record(any(Runnable.class))).thenAnswer(inv -> {
            inv.<Runnable>getArgument(0).run(); return null;
        });
        when(meterRegistry.counter(anyString())).thenReturn(mock(Counter.class));
    }

    @Test
    void buildEmbeddingText_includesContextPrefix() {
        var chunk = Map.<String, Object>of(
            "class_fqn", "com.example.BookingController",
            "method_name", "getBooking",
            "chunk_type", "METHOD",
            "annotations", List.of("@GetMapping", "@ResponseBody"),
            "content", "public Mono<Booking> getBooking(String id) { return repo.findById(id); }"
        );

        var text = invokePrivate(embeddingService, "buildEmbeddingText", chunk);

        assertThat(text).startsWith("// METHOD: com.example.BookingController.getBooking");
        assertThat(text).contains("// Annotations: @GetMapping, @ResponseBody");
        assertThat(text).contains("public Mono<Booking> getBooking");
    }

    @Test
    void buildEmbeddingText_classChunk_omitsMethodName() {
        var chunk = Map.<String, Object>of(
            "class_fqn", "com.example.BookingService",
            "method_name", "",
            "chunk_type", "CLASS",
            "annotations", List.of(),
            "content", "public class BookingService { }"
        );

        var text = invokePrivate(embeddingService, "buildEmbeddingText", chunk);

        assertThat(text).startsWith("// CLASS: com.example.BookingService\n");
        assertThat(text).doesNotContain("Annotations:");
    }

    @Test
    void buildDocument_setsAllMetadataFields() {
        var snapshotId = UUID.randomUUID();
        var chunk = Map.<String, Object>of(
            "class_fqn", "com.example.Foo",
            "method_name", "bar",
            "chunk_type", "METHOD",
            "service_name", "foo-service",
            "file_path", "Foo.java",
            "content_hash", "abc123",
            "reactive_complexity", "SIMPLE",
            "annotations", List.of("@Override"),
            "line_start", 10,
            "line_end", 25,
            "content", "void bar() {}"
        );

        var doc = invokePrivate(embeddingService, "buildDocument", snapshotId, chunk);

        assertThat(doc.getMetadata()).containsEntry("snapshot_id", snapshotId.toString());
        assertThat(doc.getMetadata()).containsEntry("service_name", "foo-service");
        assertThat(doc.getMetadata()).containsEntry("class_fqn", "com.example.Foo");
        assertThat(doc.getMetadata()).containsEntry("reactive_complexity", "SIMPLE");
    }

    @Test
    void embedSnapshot_batchesDocumentsIn64ChunkBatches() {
        var snapshotId = UUID.randomUUID();
        var chunks = IntStream.range(0, 150)
            .mapToObj(i -> codeChunkMap("com.ex.Class" + i, "method" + i))
            .toList();
        when(openSearch.search(eq("code-artifacts"), any()))
            .thenReturn(searchResponseWith(chunks), emptySearchResponse());

        embeddingService.embedSnapshot(snapshotId);

        // 150 docs / 64 batch = 3 batches (64 + 64 + 22)
        verify(vectorStoreService, times(3)).addCodeDocuments(anyList());
    }

    @Test
    void embedSnapshot_storesEvidenceInMinio() {
        var snapshotId = UUID.randomUUID();
        when(openSearch.search(eq("code-artifacts"), any()))
            .thenReturn(searchResponseWith(List.of(codeChunkMap("com.ex.C", "m"))),
                emptySearchResponse());

        embeddingService.embedSnapshot(snapshotId);

        verify(minio).putJson(eq("evidence"), contains("embeddings/code/" + snapshotId), any());
    }

    @Test
    void embedSnapshot_emptySnapshot_returnsZeroChunks() {
        var snapshotId = UUID.randomUUID();
        when(openSearch.search(eq("code-artifacts"), any()))
            .thenReturn(emptySearchResponse());

        var result = embeddingService.embedSnapshot(snapshotId);

        assertThat(result.chunksEmbedded()).isZero();
        verify(vectorStoreService, never()).addCodeDocuments(anyList());
    }

    @Test
    void fetchCodeChunks_usesSearchAfterPagination() {
        var snapshotId = UUID.randomUUID();
        var firstPage = searchResponseWith(codeChunks(5000), new Object[]{12345L});
        var secondPage = searchResponseWith(codeChunks(3000), new Object[]{67890L});
        var emptyPage = emptySearchResponse();

        when(openSearch.search(eq("code-artifacts"), any()))
            .thenReturn(firstPage, secondPage, emptyPage);

        embeddingService.embedSnapshot(snapshotId);

        verify(openSearch, times(3)).search(eq("code-artifacts"), any());
    }
}
```

**`TeiHealthIndicatorTest`** — tests health check responses.

```java
@ExtendWith(MockitoExtension.class)
class TeiHealthIndicatorTest {

    @Mock RestClient restClient;
    @Mock FlowForgeProperties props;
    @Mock FlowForgeProperties.Tei teiProps;

    @InjectMocks TeiHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        when(props.tei()).thenReturn(teiProps);
        when(teiProps.codeUrl()).thenReturn("http://tei-code:8081");
    }

    @Test
    void health_teiReachable_returnsUpWithModelDetail() {
        var requestSpec = mock(RestClient.RequestHeadersUriSpec.class);
        var responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(requestSpec);
        when(requestSpec.uri("http://tei-code:8081/health")).thenReturn(requestSpec);
        when(requestSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn("{\"status\":\"ok\"}");

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("model")).isEqualTo("codesage/codesage-large");
    }

    @Test
    void health_teiUnreachable_returnsDown() {
        when(restClient.get()).thenThrow(new RuntimeException("connection refused"));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
```

**`EmbeddingConfigTest`** — validates bean configuration.

```java
class EmbeddingConfigTest {

    @Test
    void codeEmbeddingModel_configuredWith1024Dimensions() {
        var props = mock(FlowForgeProperties.class);
        var tei = mock(FlowForgeProperties.Tei.class);
        when(props.tei()).thenReturn(tei);
        when(tei.codeUrl()).thenReturn("http://localhost:8081");

        var config = new EmbeddingConfig();
        var model = config.codeEmbeddingModel(props);

        assertThat(model).isNotNull();
        assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
    }
}
```

### Integration Tests

**`CodeEmbeddingServiceIntegrationTest`** — uses WireMock to simulate the TEI API and verifies end-to-end embedding flow.

```java
@SpringBootTest
@Tag("integration")
@WireMockTest(httpPort = 8081)
class CodeEmbeddingServiceIntegrationTest {

    @Autowired CodeEmbeddingService embeddingService;
    @MockitoBean OpenSearchClientWrapper openSearch;
    @MockitoBean MinioStorageClient minio;

    @BeforeEach
    void setupWireMock() {
        stubFor(post(urlEqualTo("/v1/embeddings"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(embeddingResponse(1024))));

        stubFor(get(urlEqualTo("/health"))
            .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"ok\"}")));
    }

    @Test
    void embedSnapshot_sendsChunksToTeiAndStoresInQdrant() {
        var snapshotId = UUID.randomUUID();
        var chunks = List.of(
            codeChunkMap("com.example.BookingController", "getBooking"),
            codeChunkMap("com.example.PaymentService", "processPayment")
        );
        when(openSearch.search(eq("code-artifacts"), any()))
            .thenReturn(searchResponseWith(chunks), emptySearchResponse());

        var result = embeddingService.embedSnapshot(snapshotId);

        assertThat(result.chunksEmbedded()).isEqualTo(2);
        assertThat(result.dimensions()).isEqualTo(1024);
        verify(postRequestedFor(urlEqualTo("/v1/embeddings"))
            .withHeader("Content-Type", containing("application/json")));
    }

    @Test
    void embedSnapshot_contextPrefixIncludedInEmbeddingRequest() {
        var snapshotId = UUID.randomUUID();
        var chunks = List.of(codeChunkMap("com.example.OrderService", "placeOrder"));
        when(openSearch.search(eq("code-artifacts"), any()))
            .thenReturn(searchResponseWith(chunks), emptySearchResponse());

        embeddingService.embedSnapshot(snapshotId);

        verify(postRequestedFor(urlEqualTo("/v1/embeddings"))
            .withRequestBody(containing("com.example.OrderService.placeOrder")));
    }

    @Test
    void embedSnapshot_healthEndpointCalledDuringActuatorCheck() {
        // TEI health stub already configured in @BeforeEach
        verify(getRequestedFor(urlEqualTo("/health")).orNot());
    }

    private String embeddingResponse(int dimensions) {
        var embedding = IntStream.range(0, dimensions)
            .mapToObj(i -> String.valueOf(Math.random()))
            .collect(Collectors.joining(","));
        return """
            {
              "data": [{"embedding": [%s], "index": 0}],
              "model": "codesage/codesage-large",
              "usage": {"prompt_tokens": 50, "total_tokens": 50}
            }
            """.formatted(embedding);
    }
}
```

### Test Fixtures & Sample Data

| Fixture file | Description |
|---|---|
| `src/test/resources/fixtures/code-chunks-small.json` | 10 code chunk maps with `class_fqn`, `method_name`, `content`, `annotations`, `chunk_type` — covers REST controller, service, repository |
| `src/test/resources/fixtures/code-chunks-large.json` | 150 code chunks for batch testing — verifies pagination and batch splitting |
| `src/test/resources/fixtures/tei-embedding-response.json` | WireMock response body for `/v1/embeddings` — 1024-dim float array |
| `src/test/resources/fixtures/opensearch-code-artifacts-page1.json` | Simulated OpenSearch search response with `sort` values for `search_after` pagination |
| `src/test/resources/fixtures/opensearch-code-artifacts-page2.json` | Second page response with remaining chunks and empty final page |

Sample data guidelines:
- Code chunks should include diverse `chunk_type` values: `METHOD`, `CLASS`, `FIELD_GROUP`.
- Annotations should include Spring stereotypes (`@RestController`, `@Service`, `@Repository`) and mappings (`@GetMapping`, `@PostMapping`).
- Include chunks with and without `method_name` to test the context prefix builder.
- Include at least one chunk with `reactive_complexity = BRANCHING` for metadata completeness.

### Mocking Strategy

| Dependency | Unit tests | Integration tests |
|---|---|---|
| `EmbeddingModel` (code) | Not called directly (Spring AI handles internally) | WireMock stub for TEI `/v1/embeddings` endpoint |
| `VectorStoreService` | Mock — verify `addCodeDocuments` calls and batch sizes | Real (or mock if Qdrant not in scope) |
| `OpenSearchClientWrapper` | Mock — return fixture code chunks, simulate pagination | `@MockitoBean` — same approach |
| `MinioStorageClient` | Mock — verify `putJson` evidence storage | `@MockitoBean` |
| `MeterRegistry` | `SimpleMeterRegistry` or mock | `SimpleMeterRegistry` |
| `RestClient` (TEI health) | Mock | WireMock handles `/health` |

- **WireMock for TEI:** The TEI API is OpenAI-compatible. Use WireMock to stub `POST /v1/embeddings` returning a JSON response with a 1024-dim float array. This avoids needing a GPU or real TEI instance in tests.
- **OpenSearch pagination:** Mock `openSearch.search()` to return multiple pages (first call returns 5000 hits with `sortValues`, second returns remaining, third returns empty) to test `search_after` logic.
- Never use a real TEI instance in unit or standard integration tests. Reserve real TEI testing for staging/E2E environments.

### CI/CD Considerations

- **JUnit tags:** Unit tests untagged. Integration tests tagged `@Tag("integration")`.
- **Gradle filtering:**
  ```kotlin
  tasks.test { useJUnitPlatform { excludeTags("integration") } }
  tasks.register<Test>("integrationTest") {
      useJUnitPlatform { includeTags("integration") }
  }
  ```
- **No GPU needed:** All tests use WireMock for TEI. CI does not require a GPU or the actual CodeSage-large model.
- **WireMock dependency:** Add `org.wiremock:wiremock-standalone` (or `wiremock-spring-boot`) as a `testImplementation` dependency.
  ```kotlin
  testImplementation("org.wiremock:wiremock-standalone:3.9.1")
  ```
- **Docker requirement:** If integration tests include Qdrant (from Stage 14), Docker is required. If using a mocked `VectorStoreService`, no Docker is needed.
- **Embedding response size:** The WireMock stub returns 1024 floats per embedding. For batch tests with 64 documents, the response JSON can be large. Pre-generate the response fixture file rather than building it dynamically in each test.
- **CI test order:** Stage 15 tests depend on Stage 14 abstractions (`VectorStoreService`). Ensure `libs:vector-store` is built before `libs:embedding` in the Gradle dependency graph.

## Verification

**Stage 15 sign-off requires all stages 1 through 15 to pass.** Run: `make verify`.

The verification report for stage 15 is `logs/stage-15.log`. It contains **cumulative output for stages 1–15** (Stage 1, then Stage 2, … then Stage 15 output).

| Check | How to verify | Pass criteria |
|---|---|---|
| TEI pod running | `kubectl get pods -n flowforge-ml -l app.kubernetes.io/name=tei-code` | Pod STATUS Running, 1/1 Ready |
| ArgoCD synced | `argocd app get flowforge-tei-code` | Sync status Healthy / Synced |
| Single embed | Embed one code snippet | 1024-dim float array |
| Batch embed | Embed 64 code chunks | All 64 stored in Qdrant |
| Full snapshot | Embed all chunks for a snapshot | All chunks in code-embeddings |
| Context prefix | Check embedded text | Starts with `// METHOD: com.example.BookingController.getBooking` |
| Metadata | Check stored Document metadata | snapshot_id, service_name, class_fqn present |
| Similarity search | Search "booking endpoint handler" | Returns relevant code chunks |
| Service filter | Search filtered to booking-service | Only booking-service results |
| Deduplication | Re-embed same snapshot | No duplicate vectors (content_hash check) |
| Health check | Actuator /health | TEI UP with model name |
| Batch metrics | Check Micrometer timers | embedding.code.batch timer populated |
| Evidence | Check MinIO evidence bucket | Embedding stats JSON present |

## Files to create

- `k8s/argocd/apps/tei-code.yaml`
- `k8s/ml-serving/tei-code/kustomization.yaml`
- `k8s/ml-serving/tei-code/deployment.yaml`
- `k8s/ml-serving/tei-code/service.yaml`
- `k8s/ml-serving/tei-code/pvc.yaml`
- `libs/embedding/build.gradle.kts`
- `libs/embedding/src/main/java/com/flowforge/embedding/config/EmbeddingConfig.java`
- `libs/embedding/src/main/java/com/flowforge/embedding/service/CodeEmbeddingService.java`
- `libs/embedding/src/main/java/com/flowforge/embedding/health/TeiHealthIndicator.java`
- `libs/embedding/src/test/java/.../CodeEmbeddingServiceIntegrationTest.java` (WireMock for TEI)

## Depends on

- Stage 08 (code chunks in OpenSearch)
- Stage 14 (Qdrant vector store)

## Produces

- Code chunk embeddings (1024-dim, CodeSage-large) in Qdrant `code-embeddings`
- Spring AI `EmbeddingModel` beans for code and log embedding
- TEI health monitoring via Actuator
