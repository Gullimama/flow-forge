# Stage 14 — Qdrant Vector Store (Spring AI VectorStore)

## Goal

Deploy Qdrant and configure **Spring AI VectorStore** abstraction for storing and querying code and log embeddings. Create collections for code chunks and log events. Provide a unified vector search interface used by the embedding and retrieval pipelines.

## Prerequisites

- Stage 01 (config framework)

## What to build

### 14.1 Qdrant deployment

> **Local dev:** A Qdrant container is defined in `docker/docker-compose.yml` (REST :6333, gRPC :6334). Use it for local iteration only.

#### ArgoCD Application

`k8s/argocd/apps/qdrant.yaml`:
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: qdrant
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "2"
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: flowforge
  destination:
    server: https://kubernetes.default.svc
    namespace: flowforge-infra
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
  sources:
    - repoURL: https://qdrant.github.io/qdrant-helm
      chart: qdrant
      targetRevision: 0.13.1
      helm:
        valueFiles:
          - $values/k8s/infrastructure/qdrant/values.yaml
    - repoURL: https://github.com/tesco/flow-forge.git
      targetRevision: main
      ref: values
```

#### Helm values

`k8s/infrastructure/qdrant/values.yaml`:
```yaml
image:
  repository: qdrant/qdrant
  tag: 1.12.4

resources:
  requests:
    cpu: "4"
    memory: 4Gi
  limits:
    cpu: "4"
    memory: 4Gi

persistence:
  enabled: true
  size: 50Gi

service:
  type: ClusterIP
  ports:
    - name: rest
      port: 6333
      targetPort: 6333
      protocol: TCP
    - name: grpc
      port: 6334
      targetPort: 6334
      protocol: TCP

nodeSelector:
  agentpool: cpupool
```

### 14.2 Spring AI Qdrant configuration

```java
@Configuration
public class QdrantConfig {

    @Bean
    public QdrantClient qdrantClient(FlowForgeProperties props) {
        return new QdrantClient(
            QdrantGrpcClient.newBuilder(
                props.qdrant().host(),
                props.qdrant().grpcPort(),
                false  // TLS
            ).build()
        );
    }

    @Bean("codeVectorStore")
    public VectorStore codeVectorStore(QdrantClient client, @Qualifier("codeEmbeddingModel") EmbeddingModel embeddingModel) {
        return QdrantVectorStore.builder(client, embeddingModel)
            .collectionName("code-embeddings")
            .initializeSchema(true)
            .build();
    }

    @Bean("logVectorStore")
    public VectorStore logVectorStore(QdrantClient client, @Qualifier("logEmbeddingModel") EmbeddingModel embeddingModel) {
        return QdrantVectorStore.builder(client, embeddingModel)
            .collectionName("log-embeddings")
            .initializeSchema(true)
            .build();
    }
}
```

### 14.3 Collection initialization

```java
@Component
public class QdrantCollectionInitializer implements ApplicationRunner {

    private final QdrantClient client;
    private final FlowForgeProperties props;

    /**
     * Ensure all required collections exist with correct configuration.
     */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        ensureCollection("code-embeddings", 1024, Distance.Cosine);
        ensureCollection("log-embeddings", 1024, Distance.Cosine);
        ensureCollection("config-embeddings", 1024, Distance.Cosine);
    }

    private void ensureCollection(String name, int dimension, Distance distance) {
        try {
            client.getCollectionInfoAsync(name).get(10, TimeUnit.SECONDS);
            log.info("Collection '{}' already exists", name);
        } catch (Exception e) {
            client.createCollectionAsync(name,
                VectorParams.newBuilder()
                    .setSize(dimension)
                    .setDistance(distance)
                    .build()
            ).get(30, TimeUnit.SECONDS);
            log.info("Created collection '{}'", name);

            // Create payload indexes for filtering
            client.createPayloadIndexAsync(name, "snapshot_id",
                PayloadSchemaType.Keyword, null, null, null, null).get();
            client.createPayloadIndexAsync(name, "service_name",
                PayloadSchemaType.Keyword, null, null, null, null).get();
        }
    }
}
```

### 14.4 Vector store service wrapper

```java
@Service
public class VectorStoreService {

    private final VectorStore codeVectorStore;
    private final VectorStore logVectorStore;
    private final QdrantClient qdrantClient;
    private final MeterRegistry meterRegistry;

    /**
     * Add code documents to the code vector store.
     */
    public void addCodeDocuments(List<Document> documents) {
        meterRegistry.timer("flowforge.vectorstore.code.add").record(() ->
            codeVectorStore.add(documents)
        );
    }

    /**
     * Add log documents to the log vector store.
     */
    public void addLogDocuments(List<Document> documents) {
        meterRegistry.timer("flowforge.vectorstore.log.add").record(() ->
            logVectorStore.add(documents)
        );
    }

    /**
     * Search code embeddings with optional metadata filter.
     */
    public List<Document> searchCode(String query, int topK, String snapshotId) {
        var request = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .similarityThreshold(0.5)
            .filterExpression(new FilterExpressionBuilder()
                .eq("snapshot_id", snapshotId).build())
            .build();

        return meterRegistry.timer("flowforge.vectorstore.code.search").record(() ->
            codeVectorStore.similaritySearch(request)
        );
    }

    /**
     * Search log embeddings with optional service filter.
     * IMPORTANT: For E5-based embeddings, callers must prefix the query
     * with "query: " (e.g., via LogEmbeddingTextBuilder.buildQueryText).
     */
    public List<Document> searchLogs(String query, int topK, String snapshotId,
                                      Optional<String> serviceName) {
        var filterBuilder = new FilterExpressionBuilder().eq("snapshot_id", snapshotId);
        var filter = serviceName
            .map(s -> filterBuilder.and(
                new FilterExpressionBuilder().eq("service_name", s)).build())
            .orElseGet(filterBuilder::build);

        var request = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .similarityThreshold(0.5)
            .filterExpression(filter)
            .build();

        return meterRegistry.timer("flowforge.vectorstore.log.search").record(() ->
            logVectorStore.similaritySearch(request)
        );
    }

    /**
     * Delete all documents for a snapshot.
     * Spring AI VectorStore.delete() takes a list of IDs, so we use the
     * Qdrant client directly for filter-based deletion.
     */
    public void deleteBySnapshot(String snapshotId) {
        var filter = Filter.newBuilder()
            .addMust(Condition.newBuilder()
                .setField(FieldCondition.newBuilder()
                    .setKey("snapshot_id")
                    .setMatch(Match.newBuilder()
                        .setKeyword(snapshotId)
                        .build())
                    .build())
                .build())
            .build();

        qdrantClient.deleteAsync("code-embeddings",
            PointsSelector.newBuilder().setFilter(filter).build(), null, null)
            .join();
        qdrantClient.deleteAsync("log-embeddings",
            PointsSelector.newBuilder().setFilter(filter).build(), null, null)
            .join();
    }
}
```

### 14.5 Qdrant health indicator

```java
@Component
public class QdrantHealthIndicator implements HealthIndicator {

    private final QdrantClient client;

    @Override
    public Health health() {
        try {
            var collections = client.listCollectionsAsync().get();
            return Health.up()
                .withDetail("collections", collections.size())
                .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
```

### 14.6 Application configuration

```yaml
# application.yml
flowforge:
  qdrant:
    host: localhost
    grpc-port: 6334
    rest-port: 6333

spring:
  ai:
    vectorstore:
      qdrant:
        host: ${flowforge.qdrant.host}
        port: ${flowforge.qdrant.grpc-port}
        collection-name: code-embeddings
```

### 14.7 Dependencies

```kotlin
// libs/vector-store/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(libs.spring.ai.qdrant.store)          // Spring AI Qdrant VectorStore
    implementation(libs.spring.boot.starter.actuator)
}
```

> **Note:** The `spring-ai-qdrant-store` entry is already defined in the version catalog (Stage 01) as:
> ```toml
> spring-ai-qdrant-store = { module = "org.springframework.ai:spring-ai-qdrant-store-spring-boot-starter", version.ref = "spring-ai" }
> ```
> The starter module auto-configures the Qdrant client and vector store beans. If you need only the
> plain library without auto-configuration, use `org.springframework.ai:spring-ai-qdrant-store` directly.

## Testing & Verification Strategy

### Unit Tests

**`VectorStoreServiceTest`** — tests the wrapper logic with mocked Spring AI `VectorStore` and `QdrantClient`.

```java
@ExtendWith(MockitoExtension.class)
class VectorStoreServiceTest {

    @Mock @Qualifier("codeVectorStore") VectorStore codeVectorStore;
    @Mock @Qualifier("logVectorStore") VectorStore logVectorStore;
    @Mock QdrantClient qdrantClient;
    @Mock MeterRegistry meterRegistry;
    @Mock Timer timer;

    @InjectMocks VectorStoreService vectorStoreService;

    @BeforeEach
    void setUp() {
        when(meterRegistry.timer(anyString())).thenReturn(timer);
        when(timer.record(any(Runnable.class))).thenAnswer(inv -> {
            inv.<Runnable>getArgument(0).run(); return null;
        });
        when(timer.record(any(Supplier.class))).thenAnswer(inv -> {
            return inv.<Supplier<?>>getArgument(0).get();
        });
    }

    @Test
    void addCodeDocuments_delegatesToCodeVectorStore() {
        var docs = List.of(new Document("test content", Map.of("snapshot_id", "snap-1")));

        vectorStoreService.addCodeDocuments(docs);

        verify(codeVectorStore).add(docs);
    }

    @Test
    void addLogDocuments_delegatesToLogVectorStore() {
        var docs = List.of(new Document("log content", Map.of("snapshot_id", "snap-1")));

        vectorStoreService.addLogDocuments(docs);

        verify(logVectorStore).add(docs);
    }

    @Test
    void searchCode_buildsFilterExpressionWithSnapshotId() {
        when(codeVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        vectorStoreService.searchCode("booking endpoint", 10, "snap-123");

        var captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(codeVectorStore).similaritySearch(captor.capture());
        var request = captor.getValue();
        assertThat(request.getQuery()).isEqualTo("booking endpoint");
        assertThat(request.getTopK()).isEqualTo(10);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.5);
    }

    @Test
    void searchLogs_withServiceName_addsServiceFilter() {
        when(logVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        vectorStoreService.searchLogs("timeout error", 5, "snap-1", Optional.of("order-service"));

        var captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(logVectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getFilterExpression()).isNotNull();
    }

    @Test
    void searchLogs_withoutServiceName_onlySnapshotFilter() {
        when(logVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        vectorStoreService.searchLogs("timeout error", 5, "snap-1", Optional.empty());

        var captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(logVectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getFilterExpression()).isNotNull();
    }

    @Test
    void deleteBySnapshot_deletesFromBothCollections() {
        var future = CompletableFuture.completedFuture(null);
        when(qdrantClient.deleteAsync(anyString(), any(PointsSelector.class), any(), any()))
            .thenReturn(future);

        vectorStoreService.deleteBySnapshot("snap-123");

        verify(qdrantClient).deleteAsync(eq("code-embeddings"), any(PointsSelector.class),
            isNull(), isNull());
        verify(qdrantClient).deleteAsync(eq("log-embeddings"), any(PointsSelector.class),
            isNull(), isNull());
    }
}
```

**`QdrantCollectionInitializerTest`** — tests collection creation and idempotent re-initialization.

```java
@ExtendWith(MockitoExtension.class)
class QdrantCollectionInitializerTest {

    @Mock QdrantClient client;
    @Mock FlowForgeProperties props;

    @InjectMocks QdrantCollectionInitializer initializer;

    @Test
    void run_existingCollection_skipsCreation() throws Exception {
        when(client.getCollectionInfoAsync("code-embeddings"))
            .thenReturn(CompletableFuture.completedFuture(mock(CollectionInfo.class)));
        when(client.getCollectionInfoAsync("log-embeddings"))
            .thenReturn(CompletableFuture.completedFuture(mock(CollectionInfo.class)));
        when(client.getCollectionInfoAsync("config-embeddings"))
            .thenReturn(CompletableFuture.completedFuture(mock(CollectionInfo.class)));

        initializer.run(mock(ApplicationArguments.class));

        verify(client, never()).createCollectionAsync(anyString(), any(VectorParams.class));
    }

    @Test
    void run_missingCollection_createsWithCorrectDimension() throws Exception {
        when(client.getCollectionInfoAsync("code-embeddings"))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));
        when(client.createCollectionAsync(eq("code-embeddings"), any(VectorParams.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(client.createPayloadIndexAsync(anyString(), anyString(), any(), any(), any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        // stub other collections as existing
        when(client.getCollectionInfoAsync("log-embeddings"))
            .thenReturn(CompletableFuture.completedFuture(mock(CollectionInfo.class)));
        when(client.getCollectionInfoAsync("config-embeddings"))
            .thenReturn(CompletableFuture.completedFuture(mock(CollectionInfo.class)));

        initializer.run(mock(ApplicationArguments.class));

        var captor = ArgumentCaptor.forClass(VectorParams.class);
        verify(client).createCollectionAsync(eq("code-embeddings"), captor.capture());
        assertThat(captor.getValue().getSize()).isEqualTo(1024);
        assertThat(captor.getValue().getDistance()).isEqualTo(Distance.Cosine);
    }

    @Test
    void run_createsPayloadIndexesAfterCollectionCreation() throws Exception {
        when(client.getCollectionInfoAsync(anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));
        when(client.createCollectionAsync(anyString(), any(VectorParams.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(client.createPayloadIndexAsync(anyString(), anyString(), any(), any(), any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));

        initializer.run(mock(ApplicationArguments.class));

        verify(client, atLeast(6)).createPayloadIndexAsync(
            anyString(), anyString(), eq(PayloadSchemaType.Keyword), any(), any(), any(), any());
    }
}
```

**`QdrantHealthIndicatorTest`** — tests health reporting.

```java
@ExtendWith(MockitoExtension.class)
class QdrantHealthIndicatorTest {

    @Mock QdrantClient client;
    @InjectMocks QdrantHealthIndicator indicator;

    @Test
    void health_qdrantReachable_returnsUp() {
        when(client.listCollectionsAsync())
            .thenReturn(CompletableFuture.completedFuture(List.of("code-embeddings", "log-embeddings")));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("collections")).isEqualTo(2);
    }

    @Test
    void health_qdrantDown_returnsDown() {
        when(client.listCollectionsAsync())
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("connection refused")));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
```

### Integration Tests

**`VectorStoreServiceIntegrationTest`** — uses Testcontainers Qdrant to test the full add/search/delete cycle.

```java
@Testcontainers
@SpringBootTest
@Tag("integration")
class VectorStoreServiceIntegrationTest {

    @Container
    static GenericContainer<?> qdrant = new GenericContainer<>("qdrant/qdrant:1.12.4")
        .withExposedPorts(6333, 6334)
        .waitingFor(Wait.forHttp("/collections").forPort(6333).forStatusCode(200));

    @DynamicPropertySource
    static void qdrantProperties(DynamicPropertyRegistry registry) {
        registry.add("flowforge.qdrant.host", qdrant::getHost);
        registry.add("flowforge.qdrant.grpc-port", () -> qdrant.getMappedPort(6334));
    }

    @Autowired VectorStoreService vectorStoreService;

    @Test
    void addAndSearchCodeDocuments_roundTrip() {
        var docs = List.of(
            new Document("public Mono<Booking> getBooking(String id) { return repo.findById(id); }",
                Map.of("snapshot_id", "snap-1", "service_name", "booking-service",
                    "class_fqn", "com.ex.BookingController")),
            new Document("public void processPayment(PaymentRequest req) { gateway.charge(req); }",
                Map.of("snapshot_id", "snap-1", "service_name", "payment-service",
                    "class_fqn", "com.ex.PaymentService"))
        );

        vectorStoreService.addCodeDocuments(docs);

        var results = vectorStoreService.searchCode("booking endpoint handler", 5, "snap-1");
        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().getMetadata().get("service_name")).isEqualTo("booking-service");
    }

    @Test
    void searchLogs_filteredByServiceName() {
        var docs = List.of(
            new Document("ERROR connection timeout to payment-gateway",
                Map.of("snapshot_id", "snap-1", "service_name", "order-service")),
            new Document("ERROR connection timeout to database",
                Map.of("snapshot_id", "snap-1", "service_name", "payment-service"))
        );

        vectorStoreService.addLogDocuments(docs);

        var results = vectorStoreService.searchLogs("connection timeout", 5, "snap-1",
            Optional.of("order-service"));
        assertThat(results).allSatisfy(doc ->
            assertThat(doc.getMetadata().get("service_name")).isEqualTo("order-service"));
    }

    @Test
    void deleteBySnapshot_removesAllDocumentsForSnapshot() {
        var docs = List.of(
            new Document("content-1", Map.of("snapshot_id", "snap-to-delete", "service_name", "svc")),
            new Document("content-2", Map.of("snapshot_id", "snap-to-delete", "service_name", "svc"))
        );
        vectorStoreService.addCodeDocuments(docs);

        vectorStoreService.deleteBySnapshot("snap-to-delete");

        var results = vectorStoreService.searchCode("content", 10, "snap-to-delete");
        assertThat(results).isEmpty();
    }

    @Test
    void searchCode_belowSimilarityThreshold_returnsNoResults() {
        var docs = List.of(
            new Document("Java Spring Boot REST controller implementation",
                Map.of("snapshot_id", "snap-1", "service_name", "svc"))
        );
        vectorStoreService.addCodeDocuments(docs);

        var results = vectorStoreService.searchCode(
            "completely unrelated query about cooking recipes", 5, "snap-1");
        assertThat(results).isEmpty(); // similarity threshold 0.5 filters these out
    }
}
```

### Test Fixtures & Sample Data

| Fixture file | Description |
|---|---|
| `src/test/resources/fixtures/code-documents.json` | 20 Spring AI `Document` objects with code content and metadata (snapshot_id, service_name, class_fqn) |
| `src/test/resources/fixtures/log-documents.json` | 20 log `Document` objects with log messages and metadata (snapshot_id, service_name, severity) |
| `src/test/resources/fixtures/search-queries.json` | 5 query/expected-result pairs for search validation |

Sample data guidelines:
- Code documents should represent real Java code snippets (method bodies, class declarations) to produce meaningful embeddings.
- Log documents should include error messages, stack trace fragments, and normal operational logs.
- Each document must have `snapshot_id` and `service_name` metadata fields for filter testing.
- Include at least 2 different `snapshot_id` values to test snapshot-scoped deletion.

### Mocking Strategy

| Dependency | Unit tests | Integration tests |
|---|---|---|
| `VectorStore` (code/log) | Mock (Mockito) — verify `add()`, `similaritySearch()` calls | Real (Spring AI + Testcontainers Qdrant) |
| `QdrantClient` | Mock — verify `deleteAsync()`, `listCollectionsAsync()` | Real (Testcontainers Qdrant) |
| `EmbeddingModel` | Not used directly in `VectorStoreService` | Provided by Spring AI auto-config (or a stub returning fixed-dimension vectors) |
| `MeterRegistry` | Mock or `SimpleMeterRegistry` | `SimpleMeterRegistry` |
| `FlowForgeProperties` | Mock with test values | `@DynamicPropertySource` from container |

- In integration tests, if a real `EmbeddingModel` (TEI) is unavailable, use a stub `EmbeddingModel` that returns random 1024-dim vectors. This isolates Qdrant testing from the embedding service.
- Never mock `QdrantClient` in integration tests — the point is to exercise the real Qdrant protocol.

### CI/CD Considerations

- **JUnit tags:** Unit tests untagged. Integration tests tagged `@Tag("integration")`.
- **Gradle filtering:**
  ```kotlin
  tasks.test { useJUnitPlatform { excludeTags("integration") } }
  tasks.register<Test>("integrationTest") {
      useJUnitPlatform { includeTags("integration") }
  }
  ```
- **Docker requirement:** Integration tests require Docker for `qdrant/qdrant:1.12.4`. CI runners must have Docker available.
- **Qdrant image caching:** Pre-pull `qdrant/qdrant:1.12.4` in the CI Docker layer to reduce test startup time.
- **Port conflicts:** Testcontainers uses random mapped ports — no risk of conflict. Do not hardcode ports 6333/6334 in test configuration.
- **Embedding model stub:** In CI, configure a `@TestConfiguration` bean that provides a fake `EmbeddingModel` returning deterministic vectors, so integration tests don't require a running TEI instance.
- **Async timeouts:** `QdrantCollectionInitializer` uses `.get(30, TimeUnit.SECONDS)`. In slow CI environments, consider increasing these timeouts via test properties.

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| Qdrant starts | `kubectl get pods -n flowforge-infra -l app.kubernetes.io/name=qdrant` | Pod Running; ArgoCD app Synced/Healthy |
| Collections created | `GET /collections` | 3 collections exist |
| Payload indexes | Check collection info | snapshot_id, service_name indexed |
| Add code docs | Add 100 Document objects | 100 points in code-embeddings |
| Add log docs | Add 100 Document objects | 100 points in log-embeddings |
| Search code | Query "booking endpoint" | Returns relevant code chunks |
| Search logs | Query "connection timeout" | Returns relevant log events |
| Service filter | Search logs for specific service | Only that service's results |
| Snapshot filter | Search filtered by snapshot_id | Only that snapshot's results |
| Delete by snapshot | Delete + re-search | Empty results |
| Health check | Actuator /health | Qdrant UP with collection count |
| Similarity threshold | Search with low similarity | No results below 0.5 |

## Files to create

- `k8s/argocd/apps/qdrant.yaml`
- `k8s/infrastructure/qdrant/values.yaml`
- `libs/vector-store/build.gradle.kts`
- `libs/vector-store/src/main/java/com/flowforge/vectorstore/config/QdrantConfig.java`
- `libs/vector-store/src/main/java/com/flowforge/vectorstore/init/QdrantCollectionInitializer.java`
- `libs/vector-store/src/main/java/com/flowforge/vectorstore/service/VectorStoreService.java`
- `libs/vector-store/src/main/java/com/flowforge/vectorstore/health/QdrantHealthIndicator.java`
- `libs/vector-store/src/test/java/.../VectorStoreServiceIntegrationTest.java` (Testcontainers Qdrant)

## Depends on

- Stage 01 (config framework)
- Spring AI EmbeddingModel (configured in Stage 15)

## Produces

- Qdrant running with 3 collections: code-embeddings, log-embeddings, config-embeddings
- Spring AI `VectorStore` beans for code and log vector stores
- Unified search/add/delete API via `VectorStoreService`
- Spring Actuator health integration
