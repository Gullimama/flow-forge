# Stage 07 — OpenSearch Setup & Index Management

## Goal

Deploy OpenSearch, create the index schemas with custom analyzers for Java code search and log analytics, and implement the OpenSearch client wrapper as a Spring component.

## Prerequisites

- Stage 01 (project structure, config framework)

## What to build

### 7.1 OpenSearch deployment

> **Local dev:** A single-node OpenSearch container is defined in `docker/docker-compose.yml` (port 9200, security disabled). Use it for local iteration only.

#### ArgoCD Application

`k8s/argocd/apps/opensearch.yaml`:
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: opensearch
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
    - repoURL: https://opensearch-project.github.io/helm-charts
      chart: opensearch
      targetRevision: 2.28.0
      helm:
        valueFiles:
          - $values/k8s/infrastructure/opensearch/values.yaml
    - repoURL: https://github.com/tesco/flow-forge.git
      targetRevision: main
      ref: values
```

#### Helm values

`k8s/infrastructure/opensearch/values.yaml`:
```yaml
replicas: 3

resources:
  requests:
    cpu: "4"
    memory: 8Gi
  limits:
    cpu: "4"
    memory: 8Gi

config:
  opensearch.yml: |
    plugins.security.disabled: true

extraEnvs:
  - name: OPENSEARCH_JAVA_OPTS
    value: "-Xmx4g -Xms4g"

persistence:
  enabled: true
  size: 100Gi

service:
  type: ClusterIP
  port: 9200

nodeSelector:
  agentpool: cpupool
```

### 7.2 Index schemas

Define 4 indexes:

#### `code-artifacts`
```json
{
  "settings": {
    "number_of_shards": 2,
    "analysis": {
      "analyzer": {
        "java_code_analyzer": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": ["java_camel_case", "lowercase", "java_stop"]
        }
      },
      "filter": {
        "java_camel_case": { "type": "word_delimiter_graph", "split_on_case_change": true },
        "java_stop": { "type": "stop", "stopwords": ["import", "package", "public", "private", "protected", "class", "interface", "void", "return", "new", "this", "super"] }
      }
    }
  },
  "mappings": {
    "properties": {
      "snapshot_id": { "type": "keyword" },
      "service_name": { "type": "keyword" },
      "file_path": { "type": "keyword" },
      "class_fqn": { "type": "keyword" },
      "method_name": { "type": "keyword" },
      "chunk_type": { "type": "keyword" },
      "annotations": { "type": "keyword" },
      "content": { "type": "text", "analyzer": "java_code_analyzer" },
      "content_hash": { "type": "keyword" },
      "reactive_complexity": { "type": "keyword" },
      "line_start": { "type": "integer" },
      "line_end": { "type": "integer" },
      "indexed_at": { "type": "date" }
    }
  }
}
```

#### `config-artifacts` — config files, Helm values, K8s manifests  
#### `runtime-events` — parsed log events (app + Istio)  
#### `anomaly-episodes` — detected anomaly episodes

### 7.3 OpenSearch client wrapper

```java
@Component
public class OpenSearchClientWrapper {

    private final OpenSearchClient client;
    private final FlowForgeProperties props;

    /** Create or update an index with settings and mappings. */
    public void ensureIndex(String indexName, String settingsJson) { ... }

    /** Bulk index documents. */
    public BulkResponse bulkIndex(String indexName, List<Map<String, Object>> documents) { ... }

    /** Search with a query DSL map. */
    public SearchResponse search(String indexName, Map<String, Object> query) { ... }

    /** Multi-match search across content fields. */
    public List<SearchHit> multiMatchSearch(String indexName, String queryText,
                                             List<String> fields, int topK) { ... }

    /** Delete documents matching a query. */
    public void deleteByQuery(String indexName, Map<String, Object> query) { ... }

    /** Get document count for an index. */
    public long getDocCount(String indexName) { ... }

    /** Health check. */
    public boolean healthCheck() { ... }
}
```

### 7.4 Index initializer

```java
@Component
public class OpenSearchIndexInitializer implements ApplicationRunner {
    private final OpenSearchClientWrapper client;

    @Override
    public void run(ApplicationArguments args) {
        client.ensureIndex("code-artifacts", loadSchema("code-artifacts.json"));
        client.ensureIndex("config-artifacts", loadSchema("config-artifacts.json"));
        client.ensureIndex("runtime-events", loadSchema("runtime-events.json"));
        client.ensureIndex("anomaly-episodes", loadSchema("anomaly-episodes.json"));
    }
}
```

### 7.5 Dependencies

```kotlin
dependencies {
    implementation(libs.opensearch.client)
}
```

## Testing & Verification Strategy

### Unit Tests

**`OpenSearchClientWrapperTest`** — validate client wrapper methods against a real OpenSearch test container.

```java
@SpringBootTest
@Testcontainers
class OpenSearchClientWrapperTest {

    @Container
    static final OpensearchContainer<?> OPENSEARCH =
            new OpensearchContainer<>("opensearchproject/opensearch:2.18.0")
                    .withSecurityEnabled(false);

    @Autowired
    OpenSearchClientWrapper wrapper;

    @Test
    void ensureIndex_createsIndexWithSettings() {
        String schema = loadResource("opensearch/code-artifacts.json");

        wrapper.ensureIndex("code-artifacts-test", schema);

        assertThat(wrapper.healthCheck()).isTrue();
        assertThat(wrapper.getDocCount("code-artifacts-test")).isZero();
    }

    @Test
    void ensureIndex_calledTwice_isIdempotent() {
        String schema = loadResource("opensearch/code-artifacts.json");

        wrapper.ensureIndex("idempotent-test", schema);
        assertDoesNotThrow(() -> wrapper.ensureIndex("idempotent-test", schema));
    }

    @Test
    void bulkIndex_indexesDocumentsAndReturnsNoErrors() {
        wrapper.ensureIndex("bulk-test", loadResource("opensearch/code-artifacts.json"));

        var docs = List.of(
                Map.<String, Object>of("service_name", "booking-service",
                        "content", "public class BookingController", "chunk_type", "CLASS_SIGNATURE"),
                Map.<String, Object>of("service_name", "payment-service",
                        "content", "public void processPayment", "chunk_type", "METHOD"));

        BulkResponse response = wrapper.bulkIndex("bulk-test", docs);

        assertThat(response.errors()).isFalse();
        refreshIndex("bulk-test");
        assertThat(wrapper.getDocCount("bulk-test")).isEqualTo(2);
    }

    @Test
    void multiMatchSearch_returnsScoredResults() {
        wrapper.ensureIndex("search-test", loadResource("opensearch/code-artifacts.json"));
        wrapper.bulkIndex("search-test", List.of(
                Map.<String, Object>of("content", "public class BookingController handles reservations",
                        "service_name", "booking-service"),
                Map.<String, Object>of("content", "public class PaymentGateway processes payments",
                        "service_name", "payment-service")));
        refreshIndex("search-test");

        var hits = wrapper.multiMatchSearch("search-test", "booking reservation",
                List.of("content"), 10);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getSourceAsMap().get("service_name")).isEqualTo("booking-service");
    }

    @Test
    void deleteByQuery_removesMatchingDocuments() {
        wrapper.ensureIndex("delete-test", loadResource("opensearch/code-artifacts.json"));
        wrapper.bulkIndex("delete-test", List.of(
                Map.<String, Object>of("snapshot_id", "snap-1", "content", "class A"),
                Map.<String, Object>of("snapshot_id", "snap-2", "content", "class B")));
        refreshIndex("delete-test");

        wrapper.deleteByQuery("delete-test",
                Map.of("term", Map.of("snapshot_id", "snap-1")));
        refreshIndex("delete-test");

        assertThat(wrapper.getDocCount("delete-test")).isEqualTo(1);
    }
}
```

**`JavaCodeAnalyzerTokenizationTest`** — verify the custom `java_code_analyzer` splits camelCase correctly.

```java
@SpringBootTest
@Testcontainers
class JavaCodeAnalyzerTokenizationTest {

    @Container
    static final OpensearchContainer<?> OPENSEARCH =
            new OpensearchContainer<>("opensearchproject/opensearch:2.18.0")
                    .withSecurityEnabled(false);

    @Autowired
    OpenSearchClientWrapper wrapper;

    @Test
    void javaCodeAnalyzer_splitsCamelCase() {
        wrapper.ensureIndex("analyzer-test", loadResource("opensearch/code-artifacts.json"));

        var tokens = analyzeText("analyzer-test", "java_code_analyzer",
                "BookingServiceController");

        assertThat(tokens).contains("booking", "service", "controller");
    }

    @Test
    void javaCodeAnalyzer_removesJavaStopwords() {
        wrapper.ensureIndex("stopword-test", loadResource("opensearch/code-artifacts.json"));

        var tokens = analyzeText("stopword-test", "java_code_analyzer",
                "public class BookingService implements Serializable");

        assertThat(tokens).contains("booking", "service", "serializable");
        assertThat(tokens).doesNotContain("public", "class", "implements");
    }

    @Test
    void javaCodeAnalyzer_handlesMethodSignatures() {
        wrapper.ensureIndex("method-test", loadResource("opensearch/code-artifacts.json"));

        var tokens = analyzeText("method-test", "java_code_analyzer",
                "processPaymentRequest");

        assertThat(tokens).contains("process", "payment", "request");
    }
}
```

**`OpenSearchIndexInitializerTest`** — verify all 4 indexes are created on application start.

```java
@SpringBootTest
@Testcontainers
class OpenSearchIndexInitializerTest {

    @Container
    static final OpensearchContainer<?> OPENSEARCH =
            new OpensearchContainer<>("opensearchproject/opensearch:2.18.0")
                    .withSecurityEnabled(false);

    @Autowired
    OpenSearchClientWrapper wrapper;

    @Test
    void onStartup_allFourIndexesExist() {
        assertThat(indexExists("code-artifacts")).isTrue();
        assertThat(indexExists("config-artifacts")).isTrue();
        assertThat(indexExists("runtime-events")).isTrue();
        assertThat(indexExists("anomaly-episodes")).isTrue();
    }

    @Test
    void codeArtifactsIndex_hasJavaCodeAnalyzer() {
        var settings = getIndexSettings("code-artifacts");
        assertThat(settings).containsKey("analysis");
    }
}
```

### Integration Tests

**`OpenSearchFullFlowIntegrationTest`** — end-to-end: index creation → bulk insert → search → delete.

```java
@SpringBootTest
@Testcontainers
@Tag("integration")
class OpenSearchFullFlowIntegrationTest {

    @Container
    static final OpensearchContainer<?> OPENSEARCH =
            new OpensearchContainer<>("opensearchproject/opensearch:2.18.0")
                    .withSecurityEnabled(false);

    @Autowired OpenSearchClientWrapper wrapper;
    @Autowired OpenSearchIndexInitializer initializer;

    @Test
    void fullLifecycle_createIndexBulkSearchDelete() {
        var snapshotId = UUID.randomUUID().toString();

        var docs = IntStream.range(0, 100)
                .mapToObj(i -> Map.<String, Object>of(
                        "snapshot_id", snapshotId,
                        "service_name", "booking-service",
                        "content", "public void method" + i + "() { return; }",
                        "chunk_type", "METHOD"))
                .toList();

        wrapper.bulkIndex("code-artifacts", docs);
        refreshIndex("code-artifacts");

        assertThat(wrapper.getDocCount("code-artifacts")).isGreaterThanOrEqualTo(100);

        var hits = wrapper.multiMatchSearch("code-artifacts", "method50",
                List.of("content"), 5);
        assertThat(hits).isNotEmpty();

        wrapper.deleteByQuery("code-artifacts",
                Map.of("term", Map.of("snapshot_id", snapshotId)));
        refreshIndex("code-artifacts");

        assertThat(wrapper.getDocCount("code-artifacts")).isZero();
    }

    @Test
    void healthCheck_returnsTrue() {
        assertThat(wrapper.healthCheck()).isTrue();
    }
}
```

### Test Fixtures & Sample Data

| Fixture file | Location | Description |
|---|---|---|
| `code-artifacts.json` | `src/main/resources/opensearch/` | Index schema with `java_code_analyzer`, camelCase filter, Java stopwords |
| `config-artifacts.json` | `src/main/resources/opensearch/` | Index schema for K8s manifests and Helm values |
| `runtime-events.json` | `src/main/resources/opensearch/` | Index schema for parsed log events |
| `anomaly-episodes.json` | `src/main/resources/opensearch/` | Index schema for detected anomaly episodes |
| `sample-code-docs.json` | `src/test/resources/fixtures/` | 20 sample code artifact documents for search testing |
| `sample-runtime-events.json` | `src/test/resources/fixtures/` | 50 sample log event documents with trace IDs |

Helper utility for `_analyze` API calls in tests:

```java
class OpenSearchTestUtils {
    static List<String> analyzeText(RestClient client, String index,
                                     String analyzer, String text) {
        var request = new Request("POST", "/" + index + "/_analyze");
        request.setJsonEntity("""
                {"analyzer": "%s", "text": "%s"}
                """.formatted(analyzer, text));
        var response = client.performRequest(request);
        // Parse tokens from response
        return extractTokens(response);
    }
}
```

### Mocking Strategy

| Dependency | Mock or Real | Rationale |
|---|---|---|
| `OpenSearchClient` | **Real** (OpenSearch Testcontainer) | The client wrapper is thin — testing against a real instance catches serialization and query DSL issues |
| `FlowForgeProperties` | **Real** (test `application-test.yml`) | Configuration is simple key-value; no reason to mock |
| `OpenSearchIndexInitializer` | **Real** (runs on context startup) | Validates actual index creation on application boot |
| `RestClient` (low-level) | **Never mocked** | Used only indirectly via `OpenSearchClient` |

### CI/CD Considerations

- **JUnit 5 tags**: `@Tag("unit")` for pure unit tests (none expected — all tests need OpenSearch), `@Tag("integration")` for all OpenSearch tests.
- **Docker requirements**: CI runners need `opensearchproject/opensearch:2.18.0`. The image is ~800 MB; cache it in CI.
- **Container startup time**: OpenSearch takes 15–30 seconds to start. Use `@Testcontainers` shared containers (`static` field) to avoid per-test restarts.
- **Index refresh**: Call `_refresh` API after bulk indexing before asserting counts or search results. OpenSearch near-real-time refresh is 1 second by default.
- **Gradle task separation**:
  ```kotlin
  tasks.test { useJUnitPlatform { excludeTags("integration") } }
  tasks.register<Test>("integrationTest") {
      useJUnitPlatform { includeTags("integration") }
  }
  ```
- **Parallel safety**: Each test class should use unique index names (e.g., `code-artifacts-<testClass>`) or clean up after itself to support parallel execution.

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| OpenSearch starts | `kubectl get pods -n flowforge-infra -l app.kubernetes.io/name=opensearch` | All 3 pods Running; ArgoCD app Synced/Healthy |
| Indexes created | `GET _cat/indices` | 4 indexes exist |
| Java analyzer | Index + search `BookingService` → camelCase split | Finds `booking` and `service` tokens |
| Bulk index | Index 100 code artifacts | All indexed |
| Multi-match search | Query "endpoint handler" | Returns relevant code |
| Service filter | Search filtered to `booking-service` | Only booking results |
| Delete by query | Delete by snapshot_id | Documents removed |
| Health check | Actuator health | Shows OpenSearch UP |

## Files to create

- `libs/common/src/main/java/com/flowforge/common/client/OpenSearchClientWrapper.java`
- `libs/common/src/main/java/com/flowforge/common/health/OpenSearchHealthIndicator.java`
- `libs/common/src/main/java/com/flowforge/common/init/OpenSearchIndexInitializer.java`
- `libs/common/src/main/resources/opensearch/code-artifacts.json`
- `libs/common/src/main/resources/opensearch/config-artifacts.json`
- `libs/common/src/main/resources/opensearch/runtime-events.json`
- `libs/common/src/main/resources/opensearch/anomaly-episodes.json`
- `k8s/argocd/apps/opensearch.yaml`
- `k8s/infrastructure/opensearch/values.yaml`
- `libs/common/src/test/java/.../OpenSearchClientWrapperIntegrationTest.java`

## Depends on

- Stage 01 (config framework)

## Produces

- Running OpenSearch with 4 indexes: code-artifacts, config-artifacts, runtime-events, anomaly-episodes
- Custom Java code analyzer for camelCase-aware search
- Typed `OpenSearchClientWrapper` Spring component
- Spring Actuator health integration
