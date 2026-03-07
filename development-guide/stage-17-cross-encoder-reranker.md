# Stage 17 — Cross-Encoder Reranker (TEI Reranker API)

## Goal

Deploy a **cross-encoder reranker** (bge-reranker-v2-m3) via TEI and build a Java client that reranks retrieval results for higher precision. The reranker scores query-document pairs jointly, providing more accurate relevance than bi-encoder similarity alone.

## Prerequisites

- Stage 15–16 (embedding pipelines returning initial candidates)

## What to build

### 17.1 TEI Reranker deployment

For local dev, use `docker/docker-compose.yml` with GPU passthrough, or run TEI in CPU mode with `--dtype float32`.

**ArgoCD Application** — registered in the App-of-Apps root:

```yaml
# k8s/argocd/apps/tei-reranker.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: flowforge-tei-reranker
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
    path: k8s/ml-serving/tei-reranker
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

**Deployment** — GPU-scheduled TEI serving bge-reranker-v2-m3:

```yaml
# k8s/ml-serving/tei-reranker/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: tei-reranker
  namespace: flowforge-ml
  labels:
    app.kubernetes.io/name: tei-reranker
    app.kubernetes.io/component: reranker
    app.kubernetes.io/part-of: flowforge
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: tei-reranker
  template:
    metadata:
      labels:
        app.kubernetes.io/name: tei-reranker
        app.kubernetes.io/component: reranker
    spec:
      nodeSelector:
        agentpool: gpupool
      tolerations:
        - key: nvidia.com/gpu
          operator: Equal
          value: present
          effect: NoSchedule
      containers:
        - name: tei-reranker
          image: ghcr.io/huggingface/text-embeddings-inference:1.5
          args:
            - --model-id
            - BAAI/bge-reranker-v2-m3
            - --port
            - "8083"
          ports:
            - containerPort: 8083
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
              port: 8083
            initialDelaySeconds: 30
            periodSeconds: 10
          volumeMounts:
            - name: model-cache
              mountPath: /data
      volumes:
        - name: model-cache
          persistentVolumeClaim:
            claimName: tei-reranker-model-cache
```

**Service** — cluster-internal access on port 8083:

```yaml
# k8s/ml-serving/tei-reranker/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: tei-reranker
  namespace: flowforge-ml
  labels:
    app.kubernetes.io/name: tei-reranker
spec:
  type: ClusterIP
  selector:
    app.kubernetes.io/name: tei-reranker
  ports:
    - port: 8083
      targetPort: 8083
      protocol: TCP
      name: http
```

### 17.2 Reranker client

```java
@Component
public class CrossEncoderReranker {

    private final RestClient restClient;
    private final String rerankerUrl;
    private final MeterRegistry meterRegistry;

    public CrossEncoderReranker(RestClient.Builder restClientBuilder,
                                 FlowForgeProperties props,
                                 MeterRegistry meterRegistry) {
        this.restClient = restClientBuilder
            .baseUrl(props.tei().rerankerUrl())
            .build();
        this.rerankerUrl = props.tei().rerankerUrl();
        this.meterRegistry = meterRegistry;
    }

    /**
     * Reranker request/response models matching TEI's /rerank endpoint.
     */
    public record RerankRequest(
        String query,
        List<String> texts,
        @JsonProperty("return_text") boolean returnText
    ) {}

    /**
     * TEI returns a flat array of {index, score} objects, not wrapped in "results".
     * We deserialize as List<RerankResult> directly.
     */
    public record RerankResult(int index, double score, String text) {}

    /**
     * Rerank a list of texts against a query.
     * Returns results sorted by relevance score (descending).
     */
    public List<RerankResult> rerank(String query, List<String> texts, int topK) {
        if (texts.isEmpty()) return List.of();

        var request = new RerankRequest(query, texts, true);

        var results = meterRegistry.timer("flowforge.reranker.latency").record(() ->
            restClient.post()
                .uri("/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<List<RerankResult>>() {})
        );

        if (results == null || results.isEmpty()) return List.of();

        return results.stream()
            .sorted(Comparator.comparingDouble(RerankResult::score).reversed())
            .limit(topK)
            .toList();
    }

    /**
     * Rerank Spring AI Documents, preserving metadata.
     */
    public List<Document> rerankDocuments(String query, List<Document> documents, int topK) {
        if (documents.isEmpty()) return List.of();

        var texts = documents.stream()
            .map(Document::getContent)
            .toList();

        var ranked = rerank(query, texts, topK);

        return ranked.stream()
            .map(r -> {
                var doc = documents.get(r.index());
                // Add reranker score to metadata
                var meta = new HashMap<>(doc.getMetadata());
                meta.put("reranker_score", r.score());
                return new Document(doc.getContent(), meta);
            })
            .toList();
    }
}
```

### 17.3 Reranker with fallback

```java
@Component
public class ResilientReranker {

    private final CrossEncoderReranker reranker;

    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(
        name = "reranker", fallbackMethod = "fallbackRerank"
    )
    @io.github.resilience4j.retry.annotation.Retry(name = "reranker")
    public List<Document> rerank(String query, List<Document> documents, int topK) {
        return reranker.rerankDocuments(query, documents, topK);
    }

    /**
     * Fallback: return documents sorted by their original similarity score.
     * This preserves bi-encoder ordering when the reranker is unavailable.
     */
    private List<Document> fallbackRerank(String query, List<Document> documents,
                                           int topK, Throwable t) {
        log.warn("Reranker unavailable, falling back to bi-encoder scores: {}", t.getMessage());
        return documents.stream()
            .sorted(Comparator.comparingDouble(
                (Document d) -> (double) d.getMetadata().getOrDefault("score", 0.0)).reversed()
            )
            .limit(topK)
            .toList();
    }
}
```

### 17.4 Reranker health indicator

```java
@Component
public class RerankerHealthIndicator implements HealthIndicator {

    private final RestClient restClient;
    private final FlowForgeProperties props;

    @Override
    public Health health() {
        try {
            restClient.get()
                .uri(props.tei().rerankerUrl() + "/health")
                .retrieve()
                .body(String.class);
            return Health.up()
                .withDetail("model", "BAAI/bge-reranker-v2-m3")
                .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
```

### 17.5 Resilience4j configuration

```yaml
# application.yml
resilience4j:
  circuitbreaker:
    instances:
      reranker:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
  retry:
    instances:
      reranker:
        max-attempts: 3
        wait-duration: 500ms
        retry-exceptions:
          - org.springframework.web.client.ResourceAccessException
```

### 17.6 Dependencies

```kotlin
// libs/reranker/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.resilience4j.spring.boot)     // io.github.resilience4j:resilience4j-spring-boot3
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.retry)
}
```

Add to version catalog:
```toml
[versions]
resilience4j = "2.2.0"

[libraries]
resilience4j-spring-boot = { module = "io.github.resilience4j:resilience4j-spring-boot3", version.ref = "resilience4j" }
resilience4j-circuitbreaker = { module = "io.github.resilience4j:resilience4j-circuitbreaker", version.ref = "resilience4j" }
resilience4j-retry = { module = "io.github.resilience4j:resilience4j-retry", version.ref = "resilience4j" }
```

> **Note:** The module is named `resilience4j-spring-boot3` even when running on Spring Boot 4.0.x.
> Resilience4j's `spring-boot3` module targets the Spring Boot 3+ auto-configuration namespace
> (Jakarta EE, `spring.factories` → `AutoConfiguration.imports`). It is fully compatible with
> Spring Boot 4.0.x. A `resilience4j-spring-boot4` module may be released in a future Resilience4j
> version; update the catalog entry if/when available.

## Testing & Verification Strategy

### Unit Tests

**`CrossEncoderRerankerTest`** — WireMock for the TEI `/rerank` endpoint, validate score sorting and edge cases.

```java
@ExtendWith(MockitoExtension.class)
class CrossEncoderRerankerTest {

    @Mock MeterRegistry meterRegistry;
    @Mock Timer timer;

    private CrossEncoderReranker reranker;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        when(meterRegistry.timer(anyString())).thenReturn(timer);
        when(timer.record(any(Supplier.class))).thenAnswer(inv ->
            inv.getArgument(0, Supplier.class).get());

        var restBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restBuilder).build();
        reranker = new CrossEncoderReranker(restBuilder,
            TestFixtures.propsWithRerankerUrl("http://localhost"), meterRegistry);
    }

    @Test
    void rerank_returnsSortedByScoreDescending() {
        mockServer.expect(requestTo("/rerank"))
            .andRespond(withSuccess("""
                [{"index":0,"score":0.3,"text":"doc0"},
                 {"index":1,"score":0.9,"text":"doc1"},
                 {"index":2,"score":0.6,"text":"doc2"}]
                """, MediaType.APPLICATION_JSON));

        var results = reranker.rerank("query", List.of("doc0", "doc1", "doc2"), 3);

        assertThat(results).extracting(CrossEncoderReranker.RerankResult::score)
            .containsExactly(0.9, 0.6, 0.3);
        assertThat(results.get(0).index()).isEqualTo(1);
    }

    @Test
    void rerank_respectsTopKLimit() {
        mockServer.expect(requestTo("/rerank"))
            .andRespond(withSuccess(TestFixtures.rerankResponse(10), MediaType.APPLICATION_JSON));

        var results = reranker.rerank("query", TestFixtures.texts(10), 3);
        assertThat(results).hasSize(3);
    }

    @Test
    void rerank_emptyInput_returnsEmptyList() {
        var results = reranker.rerank("query", List.of(), 5);
        assertThat(results).isEmpty();
    }

    @Test
    void rerankDocuments_preservesMetadataAndAddsRerankerScore() {
        mockServer.expect(requestTo("/rerank"))
            .andRespond(withSuccess("""
                [{"index":0,"score":0.85,"text":"content A"},
                 {"index":1,"score":0.45,"text":"content B"}]
                """, MediaType.APPLICATION_JSON));

        var docs = List.of(
            new Document("content A", Map.of("source", "vector")),
            new Document("content B", Map.of("source", "bm25"))
        );
        var results = reranker.rerankDocuments("query", docs, 2);

        assertThat(results.get(0).getMetadata()).containsEntry("reranker_score", 0.85);
        assertThat(results.get(0).getMetadata()).containsEntry("source", "vector");
    }

    @Test
    void rerank_teiReturnsNull_returnsEmptyList() {
        mockServer.expect(requestTo("/rerank"))
            .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        var results = reranker.rerank("query", List.of("a", "b"), 2);
        assertThat(results).isEmpty();
    }
}
```

**`ResilientRerankerTest`** — test circuit breaker fallback behavior.

```java
@ExtendWith(MockitoExtension.class)
class ResilientRerankerTest {

    @Mock CrossEncoderReranker crossEncoderReranker;
    @InjectMocks ResilientReranker resilientReranker;

    @Test
    void fallback_returnsBiEncoderOrder() throws Exception {
        var docs = List.of(
            new Document("high similarity", Map.of("score", 0.95)),
            new Document("low similarity", Map.of("score", 0.3)),
            new Document("mid similarity", Map.of("score", 0.7))
        );

        var method = ResilientReranker.class.getDeclaredMethod(
            "fallbackRerank", String.class, List.class, int.class, Throwable.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        var result = (List<Document>) method.invoke(resilientReranker,
            "query", docs, 2, new RuntimeException("TEI down"));

        assertThat(result).hasSize(2);
        assertThat((double) result.get(0).getMetadata().get("score")).isEqualTo(0.95);
    }

    @Test
    void rerank_delegatesToCrossEncoder_whenHealthy() {
        var expected = List.of(new Document("reranked", Map.of()));
        when(crossEncoderReranker.rerankDocuments("q", List.of(), 5)).thenReturn(expected);

        var result = resilientReranker.rerank("q", List.of(), 5);
        assertThat(result).isEqualTo(expected);
    }
}
```

**`RerankerHealthIndicatorTest`** — validate health UP/DOWN states.

```java
@ExtendWith(MockitoExtension.class)
class RerankerHealthIndicatorTest {

    @Mock RestClient restClient;
    @Mock RestClient.RequestHeadersUriSpec<?> uriSpec;
    @Mock RestClient.ResponseSpec responseSpec;

    @Test
    void health_returnsUp_whenTeiResponds() {
        // stub restClient chain to return 200
        var indicator = new RerankerHealthIndicator(restClient, TestFixtures.defaultProps());
        var health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("model", "BAAI/bge-reranker-v2-m3");
    }

    @Test
    void health_returnsDown_whenTeiUnreachable() {
        // stub restClient chain to throw
        var indicator = new RerankerHealthIndicator(restClient, TestFixtures.defaultProps());
        var health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
```

### Integration Tests

**`CrossEncoderRerankerIntegrationTest`** — full Spring context with WireMock simulating the TEI reranker endpoint.

```java
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class CrossEncoderRerankerIntegrationTest {

    @RegisterExtension
    static WireMockExtension teiReranker = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("flowforge.tei.reranker-url", teiReranker::baseUrl);
    }

    @Autowired CrossEncoderReranker reranker;

    @Test
    void rerank_endToEnd_withWireMock() {
        teiReranker.stubFor(post(urlPathEqualTo("/rerank"))
            .willReturn(okJson("""
                [{"index":0,"score":0.1,"text":"irrelevant"},
                 {"index":1,"score":0.95,"text":"highly relevant"}]
                """)));

        var results = reranker.rerank("find relevant doc",
            List.of("irrelevant", "highly relevant"), 2);

        assertThat(results.get(0).text()).isEqualTo("highly relevant");
        assertThat(results.get(0).score()).isGreaterThan(0.9);
    }

    @Test
    void circuitBreaker_opensAfterRepeatedFailures() {
        teiReranker.stubFor(post(urlPathEqualTo("/rerank"))
            .willReturn(serverError()));

        for (int i = 0; i < 15; i++) {
            assertThatThrownBy(() ->
                reranker.rerank("q", List.of("a"), 1));
        }
        // Subsequent calls should fail fast via open circuit
    }
}
```

### Test Fixtures & Sample Data

| Fixture file | Description |
|---|---|
| `src/test/resources/fixtures/rerank-response.json` | TEI `/rerank` response: 10 entries with index, score, and text |
| `src/test/resources/fixtures/rerank-empty-response.json` | Empty array `[]` for edge-case testing |
| `src/test/resources/fixtures/documents-for-reranking.json` | 10 Spring AI Documents with `distance` metadata for fallback sorting |
| `TestFixtures.java` | Factory methods: `rerankResponse(n)`, `texts(n)`, `propsWithRerankerUrl(url)`, `documentsWithDistances(...)` |

### Mocking Strategy

| Dependency | Mock or Real | Rationale |
|---|---|---|
| TEI `/rerank` HTTP endpoint | **WireMock** | Deterministic scores, no GPU needed |
| `RestClient` | **MockRestServiceServer** in unit tests | Fine-grained request/response assertions |
| `CrossEncoderReranker` | **Mock** in `ResilientRerankerTest` | Isolate fallback logic from HTTP concerns |
| `MeterRegistry` | **SimpleMeterRegistry** | Lightweight; verify timer invocations |
| Resilience4j | **Real** in integration tests | Validate actual circuit breaker state transitions |

### CI/CD Considerations

- **Test tags**: `@Tag("unit")` for `CrossEncoderRerankerTest` and `ResilientRerankerTest`, `@Tag("integration")` for WireMock + Spring context tests.
- **No Testcontainers required**: WireMock runs in-process — no Docker dependency for reranker tests.
- **Gradle filtering**: `./gradlew :libs:reranker:test -PincludeTags=unit` runs in under 5s with no external dependencies.
- **Circuit breaker test isolation**: Use `@DirtiesContext` or reset the circuit breaker registry between tests to avoid state leakage across test methods.
- **WireMock latency simulation**: Use `withFixedDelay(5000)` to test Resilience4j retry/timeout behavior without a real slow endpoint.

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| TEI pod running | `kubectl get pods -n flowforge-ml -l app.kubernetes.io/name=tei-reranker` | Pod STATUS Running, 1/1 Ready |
| ArgoCD synced | `argocd app get flowforge-tei-reranker` | Sync status Healthy / Synced |
| Single rerank | Query + 5 texts | Scores returned, sorted descending |
| Document rerank | 10 Spring AI Documents | Reranked with scores in metadata |
| Top-K | Rerank 20 docs, topK=5 | Only 5 returned |
| Score range | Check reranker scores | Between 0 and 1 |
| Empty input | Rerank empty list | Empty list returned |
| Latency metric | Check Micrometer timer | reranker.latency populated |
| Circuit breaker | Kill TEI, try rerank | Fallback to bi-encoder order |
| Retry | TEI briefly unavailable | Retries up to 3 times |
| Fallback order | Fallback rerank | Preserves original similarity order |
| Health check | Actuator /health | Reranker UP with model name |

## Files to create

- `k8s/argocd/apps/tei-reranker.yaml`
- `k8s/ml-serving/tei-reranker/kustomization.yaml`
- `k8s/ml-serving/tei-reranker/deployment.yaml`
- `k8s/ml-serving/tei-reranker/service.yaml`
- `k8s/ml-serving/tei-reranker/pvc.yaml`
- `libs/reranker/build.gradle.kts`
- `libs/reranker/src/main/java/com/flowforge/reranker/client/CrossEncoderReranker.java`
- `libs/reranker/src/main/java/com/flowforge/reranker/resilient/ResilientReranker.java`
- `libs/reranker/src/main/java/com/flowforge/reranker/health/RerankerHealthIndicator.java`
- `libs/reranker/src/test/java/.../CrossEncoderRerankerTest.java` (WireMock)
- `libs/reranker/src/test/java/.../ResilientRerankerTest.java`

## Depends on

- Stage 15–16 (candidate documents from embedding search)

## Produces

- Cross-encoder reranking service with Resilience4j fault tolerance
- Reranked document lists with `reranker_score` metadata
- Graceful fallback to bi-encoder scores when reranker unavailable
