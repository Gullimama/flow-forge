# Stage 18 — Hybrid GraphRAG Retrieval

## Goal

Build the hybrid retrieval pipeline that combines **graph traversal** (Neo4j), **dense vector search** (Qdrant via Spring AI), **sparse keyword search** (OpenSearch BM25), and **cross-encoder reranking** (TEI) to produce a fused, high-quality context window for the LLM synthesis stages.

## Prerequisites

- Stage 11 (Neo4j knowledge graph)
- Stage 14-16 (Qdrant embeddings)
- Stage 07 (OpenSearch keyword search)
- Stage 17 (cross-encoder reranker)

## What to build

### 18.1 Retrieval request/response models

```java
public record RetrievalRequest(
    UUID snapshotId,
    String query,
    RetrievalScope scope,
    int topK,
    Optional<String> serviceName,
    Optional<Integer> graphHops
) {
    public enum RetrievalScope { CODE, LOGS, BOTH }
}

public record RetrievalResult(
    String query,
    List<RankedDocument> documents,
    RetrievalMetadata metadata
) {}

public record RankedDocument(
    String content,
    double score,
    DocumentSource source,
    Map<String, Object> metadata
) {
    public enum DocumentSource { VECTOR_CODE, VECTOR_LOG, BM25_CODE, BM25_LOG, GRAPH }
}

public record RetrievalMetadata(
    int vectorCandidates,
    int bm25Candidates,
    int graphCandidates,
    int afterFusion,
    int afterReranking,
    long latencyMs
) {}
```

### 18.2 Individual retrieval strategies

```java
@Component
public class VectorRetriever {

    private final VectorStoreService vectorStoreService;

    public List<RankedDocument> retrieveCode(RetrievalRequest request) {
        var docs = vectorStoreService.searchCode(
            request.query(), request.topK() * 3, request.snapshotId().toString());

        return docs.stream()
            .map(d -> new RankedDocument(
                d.getContent(),
                (double) d.getMetadata().getOrDefault("distance", 0.0),
                RankedDocument.DocumentSource.VECTOR_CODE,
                d.getMetadata()
            ))
            .toList();
    }

    public List<RankedDocument> retrieveLogs(RetrievalRequest request) {
        // E5-large-v2 requires "query: " prefix for asymmetric search
        var queryText = "query: " + request.query();
        var docs = vectorStoreService.searchLogs(
            queryText, request.topK() * 3,
            request.snapshotId().toString(), request.serviceName());

        return docs.stream()
            .map(d -> new RankedDocument(
                d.getContent(),
                (double) d.getMetadata().getOrDefault("distance", 0.0),
                RankedDocument.DocumentSource.VECTOR_LOG,
                d.getMetadata()
            ))
            .toList();
    }
}

@Component
public class BM25Retriever {

    private final OpenSearchClientWrapper openSearch;

    public List<RankedDocument> retrieveCode(RetrievalRequest request) {
        var hits = openSearch.multiMatchSearch(
            "code-artifacts", request.query(),
            List.of("content", "class_fqn", "method_name", "annotations"),
            request.topK() * 3
        );

        return hits.stream()
            .map(h -> new RankedDocument(
                (String) h.getSourceAsMap().get("content"),
                h.getScore(),
                RankedDocument.DocumentSource.BM25_CODE,
                h.getSourceAsMap()
            ))
            .toList();
    }

    public List<RankedDocument> retrieveLogs(RetrievalRequest request) {
        var hits = openSearch.multiMatchSearch(
            "runtime-events", request.query(),
            List.of("template", "raw_message", "exception_class"),
            request.topK() * 3
        );

        return hits.stream()
            .map(h -> new RankedDocument(
                (String) h.getSourceAsMap().get("template"),
                h.getScore(),
                RankedDocument.DocumentSource.BM25_LOG,
                h.getSourceAsMap()
            ))
            .toList();
    }
}

@Component
public class GraphRetriever {

    private final Neo4jGraphQueryService graphQuery;

    /**
     * Retrieve graph context: service neighborhood, call chains, endpoints.
     */
    public List<RankedDocument> retrieve(RetrievalRequest request) {
        var documents = new ArrayList<RankedDocument>();

        // 1. Service neighborhood (if service specified)
        request.serviceName().ifPresent(service -> {
            int hops = request.graphHops().orElse(2);
            var neighbors = graphQuery.getServiceNeighborhood(service, hops);
            documents.add(new RankedDocument(
                formatGraphContext("Service neighborhood for " + service, neighbors),
                1.0,
                RankedDocument.DocumentSource.GRAPH,
                Map.of("type", "neighborhood", "service", service, "hops", hops)
            ));
        });

        // 2. Endpoints matching query terms
        var endpoints = graphQuery.searchEndpoints(request.query());
        if (!endpoints.isEmpty()) {
            documents.add(new RankedDocument(
                formatGraphContext("Relevant endpoints", endpoints),
                0.9,
                RankedDocument.DocumentSource.GRAPH,
                Map.of("type", "endpoints")
            ));
        }

        // 3. Complex reactive methods
        var complexMethods = graphQuery.findComplexReactiveMethods();
        if (!complexMethods.isEmpty()) {
            documents.add(new RankedDocument(
                formatGraphContext("Complex reactive methods", complexMethods),
                0.7,
                RankedDocument.DocumentSource.GRAPH,
                Map.of("type", "reactive_complex")
            ));
        }

        return documents;
    }

    private String formatGraphContext(String label, Object value) {
        if (value == null) return "";
        if (value instanceof List<?> list) {
            return list.stream()
                .map(Object::toString)
                .collect(Collectors.joining(", ", label + ": [", "]"));
        }
        return label + ": " + value;
    }
}
```

### 18.3 Reciprocal Rank Fusion (RRF)

```java
@Component
public class ReciprocalRankFusion {

    private static final int K = 60;  // RRF constant

    /**
     * Fuse multiple ranked lists using Reciprocal Rank Fusion.
     *
     * RRF score = sum over lists of 1/(k + rank_in_list)
     */
    public List<RankedDocument> fuse(List<List<RankedDocument>> rankedLists) {
        // Build content → fused score map
        var scoreMap = new LinkedHashMap<String, FusionEntry>();

        for (var list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                var doc = list.get(rank);
                var key = contentKey(doc);
                scoreMap.computeIfAbsent(key, k -> new FusionEntry(doc))
                    .addScore(1.0 / (K + rank + 1));
            }
        }

        return scoreMap.values().stream()
            .sorted(Comparator.comparingDouble(FusionEntry::fusedScore).reversed())
            .map(entry -> new RankedDocument(
                entry.document.content(),
                entry.fusedScore(),
                entry.document.source(),
                mergeMetadata(entry.document.metadata(), Map.of("rrf_score", entry.fusedScore()))
            ))
            .toList();
    }

    private String contentKey(RankedDocument doc) {
        // Use content hash if available, otherwise truncated content
        var hash = doc.metadata().get("content_hash");
        return hash != null ? hash.toString() : doc.content().substring(0, Math.min(100, doc.content().length()));
    }

    private static class FusionEntry {
        final RankedDocument document;
        double score = 0;
        void addScore(double s) { score += s; }
        double fusedScore() { return score; }
        FusionEntry(RankedDocument doc) { this.document = doc; }
    }
}
```

### 18.4 Hybrid retrieval service (orchestrator)

```java
@Service
public class HybridRetrievalService {

    private final VectorRetriever vectorRetriever;
    private final BM25Retriever bm25Retriever;
    private final GraphRetriever graphRetriever;
    private final ReciprocalRankFusion rrfFusion;
    private final ResilientReranker reranker;
    private final MeterRegistry meterRegistry;

    /**
     * Execute hybrid retrieval: vector + BM25 + graph → RRF → rerank.
     */
    public RetrievalResult retrieve(RetrievalRequest request) {
        var start = System.currentTimeMillis();

        // 1. Parallel retrieval from all sources using StructuredTaskScope (JDK 25 final API, JEP 505)
        List<RankedDocument> vectorCode = List.of(), vectorLog = List.of();
        List<RankedDocument> bm25Code = List.of(), bm25Log = List.of();
        List<RankedDocument> graphDocs = List.of();

        try (var scope = StructuredTaskScope.open(Joiner.allSuccessfulOrThrow())) {
            var vectorCodeTask = scope.fork(() -> {
                if (request.scope() != RetrievalRequest.RetrievalScope.LOGS)
                    return vectorRetriever.retrieveCode(request);
                return List.<RankedDocument>of();
            });
            var vectorLogTask = scope.fork(() -> {
                if (request.scope() != RetrievalRequest.RetrievalScope.CODE)
                    return vectorRetriever.retrieveLogs(request);
                return List.<RankedDocument>of();
            });
            var bm25CodeTask = scope.fork(() -> {
                if (request.scope() != RetrievalRequest.RetrievalScope.LOGS)
                    return bm25Retriever.retrieveCode(request);
                return List.<RankedDocument>of();
            });
            var bm25LogTask = scope.fork(() -> {
                if (request.scope() != RetrievalRequest.RetrievalScope.CODE)
                    return bm25Retriever.retrieveLogs(request);
                return List.<RankedDocument>of();
            });
            var graphTask = scope.fork(() -> graphRetriever.retrieve(request));

            scope.join();  // blocks until all tasks complete or one fails

            vectorCode = vectorCodeTask.get();
            vectorLog = vectorLogTask.get();
            bm25Code = bm25CodeTask.get();
            bm25Log = bm25LogTask.get();
            graphDocs = graphTask.get();
        } catch (StructuredTaskScope.FailedException e) {
            log.error("Parallel retrieval failed, falling back to sequential", e);
            vectorCode = vectorRetriever.retrieveCode(request);
            vectorLog = vectorRetriever.retrieveLogs(request);
            bm25Code = bm25Retriever.retrieveCode(request);
            bm25Log = bm25Retriever.retrieveLogs(request);
            graphDocs = graphRetriever.retrieve(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetrievalException("Retrieval interrupted", e);
        }

        // 2. RRF fusion
        var fusedDocs = rrfFusion.fuse(List.of(
            vectorCode, vectorLog, bm25Code, bm25Log, graphDocs
        ));

        // 3. Cross-encoder reranking (top candidates only)
        var topCandidates = fusedDocs.stream().limit(request.topK() * 2).toList();
        var springDocs = topCandidates.stream()
            .map(d -> new Document(d.content(), d.metadata()))
            .toList();

        var reranked = reranker.rerank(request.query(), springDocs, request.topK());

        var finalDocs = reranked.stream()
            .map(d -> new RankedDocument(
                d.getContent(),
                (double) d.getMetadata().getOrDefault("reranker_score", 0.0),
                resolveSource(d.getMetadata()),
                d.getMetadata()
            ))
            .toList();

        var latency = System.currentTimeMillis() - start;
        meterRegistry.timer("flowforge.retrieval.hybrid.latency").record(Duration.ofMillis(latency));

        return new RetrievalResult(
            request.query(),
            finalDocs,
            new RetrievalMetadata(
                vectorCode.size() + vectorLog.size(),
                bm25Code.size() + bm25Log.size(),
                graphDocs.size(),
                fusedDocs.size(),
                finalDocs.size(),
                latency
            )
        );
    }

    private RankedDocument.DocumentSource resolveSource(Map<String, Object> metadata) {
        var source = metadata.get("original_source");
        if (source instanceof RankedDocument.DocumentSource ds) return ds;
        if (source instanceof String s) {
            try { return RankedDocument.DocumentSource.valueOf(s); } catch (Exception ignored) {}
        }
        return RankedDocument.DocumentSource.VECTOR_CODE;
    }
}
```

### 18.5 Dependencies

```kotlin
// libs/retrieval/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:vector-store"))
    implementation(project(":libs:graph"))
    implementation(project(":libs:reranker"))
}
```

### AKS Deployment Context

This module is compiled into the FlowForge pipeline runner image (`flowforgeacr.azurecr.io/flowforge-pipeline:latest`) and executes as an Argo Workflow DAG task in the `flowforge` namespace (see Stage 28). It does not require its own Kubernetes Deployment.

**In-cluster service DNS names used:**

| Service | DNS | Port |
|---|---|---|
| OpenSearch | `flowforge-opensearch.flowforge-infra.svc.cluster.local` | 9200 |
| Qdrant | `flowforge-qdrant.flowforge-infra.svc.cluster.local` | 6334 |
| Neo4j | `flowforge-neo4j.flowforge-infra.svc.cluster.local` | 7687 |
| TEI Reranker | `tei-reranker.flowforge-ml.svc.cluster.local` | 8083 |

**Argo task resource class:** CPU (`cpupool` node selector) — the cross-encoder reranker runs on its own GPU pod.

---

## Testing & Verification Strategy

### Unit Tests

**`ReciprocalRankFusionTest`** — pure logic, no Spring context.

```java
class ReciprocalRankFusionTest {

    private final ReciprocalRankFusion rrf = new ReciprocalRankFusion();

    @Test
    void fuse_singleList_preservesOriginalOrder() {
        var list = List.of(
            rankedDoc("doc-A", 0.9, DocumentSource.VECTOR_CODE),
            rankedDoc("doc-B", 0.7, DocumentSource.VECTOR_CODE)
        );
        var fused = rrf.fuse(List.of(list));

        assertThat(fused).extracting(RankedDocument::content)
            .containsExactly("doc-A", "doc-B");
    }

    @Test
    void fuse_duplicateAcrossLists_getsBoostedScore() {
        var vectorList = List.of(
            rankedDoc("shared-doc", 0.9, DocumentSource.VECTOR_CODE),
            rankedDoc("vector-only", 0.8, DocumentSource.VECTOR_CODE)
        );
        var bm25List = List.of(
            rankedDoc("shared-doc", 5.0, DocumentSource.BM25_CODE),
            rankedDoc("bm25-only", 3.0, DocumentSource.BM25_CODE)
        );
        var fused = rrf.fuse(List.of(vectorList, bm25List));

        assertThat(fused.get(0).content()).isEqualTo("shared-doc");
        double sharedScore = fused.get(0).score();
        double singleSourceScore = fused.stream()
            .filter(d -> d.content().equals("vector-only"))
            .findFirst().orElseThrow().score();
        assertThat(sharedScore).isGreaterThan(singleSourceScore);
    }

    @Test
    void fuse_emptyLists_returnsEmpty() {
        var fused = rrf.fuse(List.of(List.of(), List.of()));
        assertThat(fused).isEmpty();
    }

    @Test
    void fuse_rrfScoreFormula_isCorrect() {
        // k=60: rank 0 → 1/(60+1)=0.01639, rank 1 → 1/(60+2)=0.01613
        var list = List.of(
            rankedDoc("first", 1.0, DocumentSource.VECTOR_CODE),
            rankedDoc("second", 0.5, DocumentSource.VECTOR_CODE)
        );
        var fused = rrf.fuse(List.of(list));
        assertThat(fused.get(0).score()).isCloseTo(1.0 / 61, within(0.0001));
    }

    @Test
    void fuse_fiveSourceLists_deduplicatesCorrectly() {
        var vectorCode = List.of(rankedDoc("A", 0.9, DocumentSource.VECTOR_CODE));
        var vectorLog = List.of(rankedDoc("B", 0.8, DocumentSource.VECTOR_LOG));
        var bm25Code = List.of(rankedDoc("A", 5.0, DocumentSource.BM25_CODE));
        var bm25Log = List.of(rankedDoc("C", 3.0, DocumentSource.BM25_LOG));
        var graph = List.of(rankedDoc("A", 1.0, DocumentSource.GRAPH));

        var fused = rrf.fuse(List.of(vectorCode, vectorLog, bm25Code, bm25Log, graph));

        long distinctContents = fused.stream().map(RankedDocument::content).distinct().count();
        assertThat(distinctContents).isEqualTo(fused.size());
        assertThat(fused.get(0).content()).isEqualTo("A"); // appears in 3 lists
    }

    private RankedDocument rankedDoc(String content, double score, DocumentSource source) {
        return new RankedDocument(content, score, source, Map.of("content_hash", content));
    }
}
```

**`VectorRetrieverTest`** — mock `VectorStoreService`, verify query prefix.

```java
@ExtendWith(MockitoExtension.class)
class VectorRetrieverTest {

    @Mock VectorStoreService vectorStoreService;
    @InjectMocks VectorRetriever vectorRetriever;

    @Test
    void retrieveLogs_prependsQueryPrefixForE5() {
        when(vectorStoreService.searchLogs(anyString(), anyInt(), anyString(), any()))
            .thenReturn(List.of());

        vectorRetriever.retrieveLogs(TestFixtures.retrievalRequest("connection failure"));

        verify(vectorStoreService).searchLogs(
            argThat(q -> q.startsWith("query: ")), anyInt(), anyString(), any());
    }

    @Test
    void retrieveCode_mapsDocumentsToRankedDocumentWithVectorCodeSource() {
        var doc = new Document("class Foo {}", Map.of("distance", 0.85));
        when(vectorStoreService.searchCode(anyString(), anyInt(), anyString()))
            .thenReturn(List.of(doc));

        var results = vectorRetriever.retrieveCode(TestFixtures.retrievalRequest("Foo"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).source()).isEqualTo(DocumentSource.VECTOR_CODE);
    }
}
```

**`HybridRetrievalServiceTest`** — mock all retrievers and reranker, verify orchestration.

```java
@ExtendWith(MockitoExtension.class)
class HybridRetrievalServiceTest {

    @Mock VectorRetriever vectorRetriever;
    @Mock BM25Retriever bm25Retriever;
    @Mock GraphRetriever graphRetriever;
    @Mock ReciprocalRankFusion rrfFusion;
    @Mock ResilientReranker reranker;
    @Mock MeterRegistry meterRegistry;
    @Mock Timer timer;

    @InjectMocks HybridRetrievalService service;

    @BeforeEach
    void stubMetrics() {
        when(meterRegistry.timer(anyString())).thenReturn(timer);
    }

    @Test
    void retrieve_codeScope_doesNotQueryLogRetrievers() {
        var request = TestFixtures.retrievalRequest("booking",
            RetrievalRequest.RetrievalScope.CODE);
        when(rrfFusion.fuse(anyList())).thenReturn(List.of());
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of());

        service.retrieve(request);

        verify(vectorRetriever, never()).retrieveLogs(any());
        verify(bm25Retriever, never()).retrieveLogs(any());
    }

    @Test
    void retrieve_populatesMetadataWithCandidateCounts() {
        var request = TestFixtures.retrievalRequest("test", RetrievalRequest.RetrievalScope.BOTH);
        when(vectorRetriever.retrieveCode(any())).thenReturn(TestFixtures.rankedDocs(5));
        when(vectorRetriever.retrieveLogs(any())).thenReturn(TestFixtures.rankedDocs(3));
        when(bm25Retriever.retrieveCode(any())).thenReturn(TestFixtures.rankedDocs(4));
        when(bm25Retriever.retrieveLogs(any())).thenReturn(TestFixtures.rankedDocs(2));
        when(graphRetriever.retrieve(any())).thenReturn(TestFixtures.rankedDocs(1));
        when(rrfFusion.fuse(anyList())).thenReturn(TestFixtures.rankedDocs(10));
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of());

        var result = service.retrieve(request);

        assertThat(result.metadata().vectorCandidates()).isEqualTo(8);
        assertThat(result.metadata().bm25Candidates()).isEqualTo(6);
        assertThat(result.metadata().graphCandidates()).isEqualTo(1);
    }

    @Test
    void resolveSource_handlesStringAndEnumValues() throws Exception {
        var method = HybridRetrievalService.class.getDeclaredMethod(
            "resolveSource", Map.class);
        method.setAccessible(true);

        var fromEnum = method.invoke(service,
            Map.of("original_source", DocumentSource.BM25_LOG));
        assertThat(fromEnum).isEqualTo(DocumentSource.BM25_LOG);

        var fromString = method.invoke(service,
            Map.of("original_source", "GRAPH"));
        assertThat(fromString).isEqualTo(DocumentSource.GRAPH);
    }
}
```

### Integration Tests

**`HybridRetrievalServiceIntegrationTest`** — combines WireMock for external APIs with Testcontainers for data stores.

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("integration")
class HybridRetrievalServiceIntegrationTest {

    @Container
    static QdrantContainer qdrant = new QdrantContainer("qdrant/qdrant:v1.12.0");

    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5.26-community")
        .withoutAuthentication();

    @Container
    static OpensearchContainer opensearch = new OpensearchContainer(
        "opensearchproject/opensearch:2.18.0");

    @RegisterExtension
    static WireMockExtension teiReranker = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.vectorstore.qdrant.host", qdrant::getHost);
        registry.add("spring.ai.vectorstore.qdrant.port", () -> qdrant.getGrpcPort());
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.opensearch.uris", opensearch::getHttpHostAddress);
        registry.add("flowforge.tei.reranker-url", teiReranker::baseUrl);
    }

    @Autowired HybridRetrievalService hybridRetrievalService;

    @Test
    void retrieve_endToEnd_combinesAllSources() {
        // Seed test data into Qdrant, Neo4j, OpenSearch
        // ...
        teiReranker.stubFor(post("/rerank")
            .willReturn(okJson(TestFixtures.rerankResponse(5))));

        var request = new RetrievalRequest(TestFixtures.SNAPSHOT_ID,
            "booking service timeout", RetrievalRequest.RetrievalScope.BOTH,
            10, Optional.of("booking-service"), Optional.of(2));

        var result = hybridRetrievalService.retrieve(request);

        assertThat(result.documents()).isNotEmpty();
        assertThat(result.metadata().latencyMs()).isGreaterThan(0);
    }
}
```

### Test Fixtures & Sample Data

| Fixture file | Description |
|---|---|
| `src/test/resources/fixtures/ranked-docs-vector.json` | 10 `RankedDocument` entries with `VECTOR_CODE` / `VECTOR_LOG` sources and distance scores |
| `src/test/resources/fixtures/ranked-docs-bm25.json` | 10 `RankedDocument` entries with `BM25_CODE` / `BM25_LOG` sources and BM25 scores |
| `src/test/resources/fixtures/ranked-docs-graph.json` | 3 `RankedDocument` entries with `GRAPH` source (neighborhood, endpoints, complex methods) |
| `src/test/resources/fixtures/neo4j-seed.cypher` | Cypher script: 5 services, 8 HTTP call edges, 2 Kafka topics, endpoint nodes |
| `src/test/resources/fixtures/opensearch-seed.json` | Bulk-index payload for `code-artifacts` and `runtime-events` indices |
| `TestFixtures.java` | Factory methods: `retrievalRequest(query)`, `retrievalRequest(query, scope)`, `rankedDocs(n)`, `rerankResponse(n)` |

### Mocking Strategy

| Dependency | Mock or Real | Rationale |
|---|---|---|
| `VectorRetriever` | **Mock** in orchestrator unit tests | Isolate fusion/reranking logic |
| `BM25Retriever` | **Mock** in orchestrator unit tests | Avoid OpenSearch dependency |
| `GraphRetriever` | **Mock** in orchestrator unit tests | Avoid Neo4j dependency |
| `ReciprocalRankFusion` | **Real** always | Pure logic, no I/O |
| `ResilientReranker` | **Mock** in unit tests, **WireMock** in integration | Test fallback separately |
| `VectorStoreService` | **Mock** in `VectorRetrieverTest`, **Qdrant Testcontainer** in integration | Verify query prefix in unit, full search in integration |
| `StructuredTaskScope` | **Real** | Tests parallel execution; fallback tested by having a retriever throw |

### CI/CD Considerations

- **Test tags**: `@Tag("unit")` for RRF / retriever / orchestrator unit tests, `@Tag("integration")` for multi-container tests.
- **Docker images required**: `qdrant/qdrant:v1.12.0`, `neo4j:5.26-community`, `opensearchproject/opensearch:2.18.0` for Testcontainers.
- **Resource requirements**: Integration tests with 3 Testcontainers + WireMock need at least 4 GB RAM in CI. Configure Gradle with `-Xmx4g`.
- **Gradle task**: `./gradlew :libs:retrieval:test -PincludeTags=unit` for fast feedback; `./gradlew :libs:retrieval:test -PincludeTags=integration` for full pipeline validation.
- **Testcontainer startup**: Neo4j + OpenSearch can take 20–30s. Use `@Testcontainers(parallel = true)` to start all containers concurrently.
- **StructuredTaskScope tests**: Require JDK 25+. StructuredTaskScope is a final API in Java 25 (JEP 505), so `--enable-preview` is not needed. Ensure CI JDK matches.

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| Vector code retrieval | Query "booking endpoint" | Returns code chunks |
| Vector log retrieval | Query "connection timeout" | Returns log events |
| BM25 code retrieval | Query "BookingController" | Returns exact match |
| BM25 log retrieval | Query "NullPointerException" | Returns exception logs |
| Graph retrieval | Query with service filter | Returns neighborhood |
| RRF fusion | 3 lists with overlap | Deduplicated, fused scores |
| RRF ordering | Different rank positions | Higher fused score for multi-source docs |
| Reranking | Fused list → rerank | Top-K reranked by cross-encoder |
| Parallel retrieval | Full pipeline with StructuredTaskScope | All 5 sources queried in parallel |
| Scope filter | CODE scope | No log results |
| Service filter | Filter to booking-service | Only booking results |
| Fallback | Kill reranker | Falls back to RRF order |
| Latency metric | Check timer | retrieval.hybrid.latency populated |
| Metadata | Check final documents | rrf_score + reranker_score in metadata |

## Files to create

- `libs/retrieval/build.gradle.kts`
- `libs/retrieval/src/main/java/com/flowforge/retrieval/model/RetrievalRequest.java`
- `libs/retrieval/src/main/java/com/flowforge/retrieval/model/RetrievalResult.java`
- `libs/retrieval/src/main/java/com/flowforge/retrieval/model/RankedDocument.java`
- `libs/retrieval/src/main/java/com/flowforge/retrieval/strategy/VectorRetriever.java`
- `libs/retrieval/src/main/java/com/flowforge/retrieval/strategy/BM25Retriever.java`
- `libs/retrieval/src/main/java/com/flowforge/retrieval/strategy/GraphRetriever.java`
- `libs/retrieval/src/main/java/com/flowforge/retrieval/fusion/ReciprocalRankFusion.java`
- `libs/retrieval/src/main/java/com/flowforge/retrieval/service/HybridRetrievalService.java`
- `libs/retrieval/src/test/java/.../ReciprocalRankFusionTest.java`
- `libs/retrieval/src/test/java/.../HybridRetrievalServiceIntegrationTest.java`

## Depends on

- Stage 07 (OpenSearch BM25)
- Stage 11 (Neo4j graph)
- Stage 14-16 (Qdrant vectors)
- Stage 17 (cross-encoder reranker)

## Produces

- Hybrid retrieval pipeline: vector + BM25 + graph → RRF → cross-encoder rerank
- Parallel retrieval using Java 25 StructuredTaskScope
- Fused, reranked context window for LLM synthesis
