# Stage 03 — MinIO Raw Evidence Store

## Goal

Deploy MinIO, create the bucket layout, and implement the MinIO client wrapper as a Spring component that all ingestion and parsing services will use to read/write raw evidence.

## Prerequisites

- Stage 01 completed (project structure, config framework)

## What to build

### 3.1 MinIO deployment

> **Local dev only:** a lightweight MinIO container is defined in
> `docker/docker-compose.yml` for laptop use. Everything below targets the
> AKS production path via ArgoCD GitOps.

#### ArgoCD Application (`k8s/argocd/apps/minio.yaml`)

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: flowforge-minio
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "1"
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: flowforge
  sources:
    - repoURL: https://charts.bitnami.com/bitnami
      chart: minio
      targetRevision: 14.*
      helm:
        valueFiles:
          - $values/k8s/infrastructure/minio/values.yaml
    - repoURL: https://github.com/tesco/flow-forge.git
      targetRevision: HEAD
      ref: values
  destination:
    server: https://kubernetes.default.svc
    namespace: flowforge-infra
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - ServerSideApply=true
```

#### Helm values (`k8s/infrastructure/minio/values.yaml`)

```yaml
mode: standalone

auth:
  existingSecret: flowforge-minio-credentials   # keys: root-user, root-password
  secretKeys:
    rootUserKey: root-user
    rootPasswordKey: root-password

persistence:
  enabled: true
  size: 100Gi
  storageClass: managed-csi

defaultBuckets: >-
  raw-git,
  raw-logs,
  parsed-code,
  parsed-logs,
  graph-artifacts,
  research-output,
  model-artifacts,
  evidence

resources:
  requests:
    cpu: "2"
    memory: 4Gi
  limits:
    cpu: "4"
    memory: 8Gi

nodeSelector:
  agentpool: cpupool

readinessProbe:
  enabled: true
  initialDelaySeconds: 10
  periodSeconds: 10
livenessProbe:
  enabled: true
  initialDelaySeconds: 30
  periodSeconds: 10

metrics:
  enabled: true
  serviceMonitor:
    enabled: true
    namespace: flowforge-obs
```

### 3.2 Bucket layout

Create these buckets on startup (idempotent):

| Bucket | Purpose |
|---|---|
| `raw-git` | Git snapshot archives and extracted source trees |
| `raw-logs` | Downloaded zip files and extracted log content |
| `parsed-code` | Structured JSON from code parsing |
| `parsed-logs` | Normalized JSON from log parsing |
| `graph-artifacts` | Neo4j export files, GNN model artifacts |
| `research-output` | Generated markdown and JSON outputs |
| `model-artifacts` | MLflow model checkpoints |
| `evidence` | Parse reports, anomaly reports, synthesis intermediates |

### 3.3 MinIO client wrapper

```java
@Component
public class MinioStorageClient {

    private final MinioClient minioClient;
    private final ObjectMapper objectMapper;

    public MinioStorageClient(FlowForgeProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.minioClient = MinioClient.builder()
            .endpoint(props.minio().endpoint())
            .credentials(props.minio().accessKey(), props.minio().secretKey())
            .build();
    }

    /** Create all required buckets if they don't exist. */
    public void ensureBuckets() { ... }

    /** Upload bytes. Returns the full path (bucket/key). */
    public String putObject(String bucket, String key, byte[] data, String contentType) { ... }

    /** Serialize an object to JSON and upload. */
    public String putJson(String bucket, String key, Object obj) { ... }

    /** Download an object as bytes. */
    public byte[] getObject(String bucket, String key) { ... }

    /** Download and deserialize a JSON object. */
    public <T> T getJson(String bucket, String key, Class<T> type) { ... }

    /** Download and deserialize a JSON object to a TypeReference. */
    public <T> T getJson(String bucket, String key, TypeReference<T> typeRef) { ... }

    /** Stream download for large objects. */
    public InputStream getObjectStream(String bucket, String key) { ... }

    /** List objects under a prefix. */
    public List<MinioObjectInfo> listObjects(String bucket, String prefix) { ... }

    /** Check if an object exists. */
    public boolean objectExists(String bucket, String key) { ... }

    /** Delete a single object. */
    public void deleteObject(String bucket, String key) { ... }

    /** Copy an object between buckets/keys. */
    public void copyObject(String srcBucket, String srcKey, String dstBucket, String dstKey) { ... }

    /** Check MinIO connectivity. Contributes to Spring Actuator health. */
    public boolean healthCheck() { ... }
}
```

```java
public record MinioObjectInfo(
    String bucket,
    String key,
    long size,
    Instant lastModified,
    String etag
) {}
```

### 3.4 Spring Actuator health indicator

```java
@Component
public class MinioHealthIndicator implements HealthIndicator {
    private final MinioStorageClient storage;

    @Override
    public Health health() {
        return storage.healthCheck()
            ? Health.up().withDetail("buckets", 8).build()
            : Health.down().withDetail("error", "MinIO unreachable").build();
    }
}
```

### 3.5 Key path conventions

All services must follow consistent key paths:

```
raw-git/<snapshot_id>/source/<service_name>/...
raw-git/<snapshot_id>/manifests/...
raw-git/<snapshot_id>/helm/...
raw-git/<snapshot_id>/istio/...

raw-logs/<batch_id>/<blob_name>/archive.zip
raw-logs/<batch_id>/<blob_name>/extracted/<filename>

parsed-code/<snapshot_id>/<service_name>/<artifact_type>/<artifact_key>.json
parsed-logs/<batch_id>/<service_name>/<event_id>.json

graph-artifacts/<run_id>/neo4j-export.cypher
graph-artifacts/<run_id>/gnn-checkpoint.onnx

research-output/<run_id>/system-flows-research.md
research-output/<run_id>/flow-catalog.json
research-output/<run_id>/service-catalog.json

model-artifacts/<model_name>/<version>/model.onnx
```

### 3.6 Dependencies

```kotlin
dependencies {
    implementation(libs.minio)
}
```

## Testing & Verification Strategy

### Unit Tests

**Test class:** `libs/common/src/test/java/com/flowforge/common/client/MinioStorageClientUnitTest.java`

Tests serialization logic and key-path construction without a running MinIO instance by mocking the underlying `MinioClient`.

```java
@ExtendWith(MockitoExtension.class)
class MinioStorageClientUnitTest {

    @Mock private MinioClient minioClient;
    @Mock private ObjectMapper objectMapper;

    private MinioStorageClient storageClient;

    @BeforeEach
    void setUp() {
        storageClient = new MinioStorageClient(minioClient, objectMapper);
    }

    @Test
    void putJsonSerializesObjectBeforeUpload() throws Exception {
        var record = new SnapshotMetadata(
            UUID.randomUUID(), "https://github.com/org/repo", "master",
            "sha1", SnapshotMetadata.SnapshotType.BASELINE,
            Instant.now(), List.of()
        );
        byte[] expectedBytes = "{\"snapshotId\":\"...\"}".getBytes();
        when(objectMapper.writeValueAsBytes(record)).thenReturn(expectedBytes);

        storageClient.putJson("parsed-code", "snap/meta.json", record);

        verify(objectMapper).writeValueAsBytes(record);
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void getJsonDeserializesToExpectedType() throws Exception {
        byte[] data = "{\"bucket\":\"raw-git\",\"key\":\"k\"}".getBytes();
        var stream = new ByteArrayInputStream(data);
        when(minioClient.getObject(any(GetObjectArgs.class)))
            .thenReturn(new GetObjectResponse(null, "raw-git", null, "k", stream));
        var expected = new MinioObjectInfo("raw-git", "k", 100L, Instant.now(), "etag");
        when(objectMapper.readValue(any(byte[].class), eq(MinioObjectInfo.class)))
            .thenReturn(expected);

        var result = storageClient.getJson("raw-git", "k", MinioObjectInfo.class);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void objectExistsReturnsFalseOnNoSuchKeyException() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
            .thenThrow(new ErrorResponseException(
                new ErrorResponse("NoSuchKey", "", "", "", "", "", ""),
                null, ""));

        assertThat(storageClient.objectExists("raw-git", "missing.txt")).isFalse();
    }

    @Test
    void healthCheckReturnsFalseOnException() throws Exception {
        when(minioClient.listBuckets()).thenThrow(new RuntimeException("connection refused"));

        assertThat(storageClient.healthCheck()).isFalse();
    }
}
```

**Test class:** `libs/common/src/test/java/com/flowforge/common/health/MinioHealthIndicatorTest.java`

```java
@ExtendWith(MockitoExtension.class)
class MinioHealthIndicatorTest {

    @Mock private MinioStorageClient storageClient;
    @InjectMocks private MinioHealthIndicator indicator;

    @Test
    void healthUpWhenMinioReachable() {
        when(storageClient.healthCheck()).thenReturn(true);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.UP);
    }

    @Test
    void healthDownWhenMinioUnreachable() {
        when(storageClient.healthCheck()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }
}
```

### Integration Tests

**Test class:** `libs/common/src/test/java/com/flowforge/common/client/MinioStorageClientIntegrationTest.java`

Uses Testcontainers MinIO to run all storage operations against a real MinIO instance.

```java
@Testcontainers
@Tag("integration")
class MinioStorageClientIntegrationTest {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest")
        .withUserName("minioadmin")
        .withPassword("minioadmin");

    private MinioStorageClient storageClient;
    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        MinioClient client = MinioClient.builder()
            .endpoint(minio.getS3URL())
            .credentials("minioadmin", "minioadmin")
            .build();
        storageClient = new MinioStorageClient(client, objectMapper);
    }

    @Test
    void ensureBucketsCreatesAllEightBuckets() {
        storageClient.ensureBuckets();

        List<String> expected = List.of(
            "raw-git", "raw-logs", "parsed-code", "parsed-logs",
            "graph-artifacts", "research-output", "model-artifacts", "evidence"
        );
        for (String bucket : expected) {
            assertThat(storageClient.objectExists(bucket, "")).isFalse();
        }
    }

    @Test
    void putAndGetObjectRoundTrip() {
        storageClient.ensureBuckets();
        byte[] data = "hello flowforge".getBytes(StandardCharsets.UTF_8);

        storageClient.putObject("raw-git", "test/hello.txt", data, "text/plain");
        byte[] retrieved = storageClient.getObject("raw-git", "test/hello.txt");

        assertThat(retrieved).isEqualTo(data);
    }

    @Test
    void putAndGetJsonRoundTrip() {
        storageClient.ensureBuckets();
        var meta = new SnapshotMetadata(
            UUID.randomUUID(), "https://github.com/org/repo", "master",
            "sha1", SnapshotMetadata.SnapshotType.BASELINE,
            Instant.now(), List.of("a.java", "b.yaml")
        );

        storageClient.putJson("parsed-code", "snap/meta.json", meta);
        var retrieved = storageClient.getJson("parsed-code", "snap/meta.json",
            SnapshotMetadata.class);

        assertThat(retrieved.repoUrl()).isEqualTo(meta.repoUrl());
        assertThat(retrieved.changedFiles()).containsExactly("a.java", "b.yaml");
    }

    @Test
    void listObjectsReturnsCorrectCount() {
        storageClient.ensureBuckets();
        for (int i = 0; i < 5; i++) {
            storageClient.putObject("evidence", "report/" + i + ".json",
                "{}".getBytes(), "application/json");
        }

        List<MinioObjectInfo> objects = storageClient.listObjects("evidence", "report/");

        assertThat(objects).hasSize(5);
    }

    @Test
    void streamDownloadReturnsFullContent() throws Exception {
        storageClient.ensureBuckets();
        byte[] largePayload = new byte[1024 * 1024]; // 1MB
        new Random().nextBytes(largePayload);
        storageClient.putObject("raw-logs", "large.bin", largePayload,
            "application/octet-stream");

        try (InputStream stream = storageClient.getObjectStream("raw-logs", "large.bin")) {
            byte[] downloaded = stream.readAllBytes();
            assertThat(downloaded).isEqualTo(largePayload);
        }
    }

    @Test
    void deleteObjectRemovesIt() {
        storageClient.ensureBuckets();
        storageClient.putObject("evidence", "temp.txt", "x".getBytes(), "text/plain");
        assertThat(storageClient.objectExists("evidence", "temp.txt")).isTrue();

        storageClient.deleteObject("evidence", "temp.txt");

        assertThat(storageClient.objectExists("evidence", "temp.txt")).isFalse();
    }

    @Test
    void copyObjectBetweenBuckets() {
        storageClient.ensureBuckets();
        storageClient.putObject("raw-git", "src.txt", "data".getBytes(), "text/plain");

        storageClient.copyObject("raw-git", "src.txt", "evidence", "dst.txt");

        byte[] copied = storageClient.getObject("evidence", "dst.txt");
        assertThat(new String(copied)).isEqualTo("data");
    }

    @Test
    void healthCheckReturnsTrueWithRunningMinio() {
        assertThat(storageClient.healthCheck()).isTrue();
    }
}
```

### Test Fixtures & Sample Data

| File | Description |
|---|---|
| `src/test/resources/fixtures/sample-source-file.java` | A small Java source file used as upload payload |
| `src/test/resources/fixtures/sample-parsed-output.json` | A JSON parse artifact for put/get JSON round-trip testing |
| `src/test/resources/fixtures/sample-log-archive.zip` | A small zip file (~1KB) to test binary upload/download |
| `src/test/resources/fixtures/minio-key-paths.txt` | Reference list of expected key path patterns for validation tests |

### Mocking Strategy

| Component | Strategy |
|---|---|
| `MinioClient` (io.minio) | **Mocked** in unit tests — verify method calls and error handling |
| `ObjectMapper` | **Mocked** in unit tests to isolate serialization concerns; **real** in integration tests |
| `MinioStorageClient` | **Real** in integration tests against Testcontainers MinIO |
| `MinioHealthIndicator` | Tested with **mocked** `MinioStorageClient` in unit tests |
| `FlowForgeProperties` | Not needed directly — MinIO config is extracted in `@BeforeEach` for integration tests |

### CI/CD Considerations

- **Test tags:** `@Tag("unit")` for mock-based tests, `@Tag("integration")` for Testcontainers MinIO tests.
- **Docker requirements:** CI runners need Docker for `MinIOContainer`. The MinIO image is lightweight (~100MB) and starts in <3 seconds.
- **Testcontainers reuse:** Enable container reuse for local development:
  ```properties
  # ~/.testcontainers.properties
  testcontainers.reuse.enable=true
  ```
- **CI pipeline stages:** `compile` → `unit-test` → `integration-test` (Docker required) → `lint`.
- **Image pull caching:** Pre-pull `minio/minio:latest` in CI to avoid pull latency during test runs.
- **Bucket idempotency:** Integration tests call `ensureBuckets()` in `@BeforeEach` — safe to run repeatedly. No teardown needed since each Testcontainers instance is isolated.

## Verification

**Stage 3 sign-off requires all stages 1 through 3 to pass.** Run: `make verify`.

The verification report for stage 3 is `logs/stage-03.log`. It contains **cumulative output for stages 1–3** (Stage 1, then Stage 2, then Stage 3 output).

| Check | How to verify | Pass criteria |
|---|---|---|
| MinIO pod healthy | `kubectl get pods -n flowforge-infra -l app.kubernetes.io/name=minio` | Pod `Running`, `1/1 Ready` |
| ArgoCD app synced | `argocd app get flowforge-minio` | Status: `Synced`, Health: `Healthy` |
| Buckets created | `ensureBuckets()` + list | All 8 buckets exist |
| Put + get object | Unit test: upload bytes, download, compare | Content matches |
| Put + get JSON | Unit test: upload record, download, deserialize | Round-trip matches |
| List objects | Unit test: upload 5 objects, list | Returns 5 items |
| Object exists | Unit test: existing + non-existing | True / False correctly |
| Stream download | Unit test: upload 10MB file, stream read | Full content received |
| Delete object | Unit test: upload, delete, check | Object gone |
| Health indicator | Actuator `/health` with running MinIO | Shows UP |
| Key path compliance | Code review | All keys follow convention |

## Files to create

- `libs/common/src/main/java/com/flowforge/common/client/MinioStorageClient.java`
- `libs/common/src/main/java/com/flowforge/common/client/MinioObjectInfo.java`
- `libs/common/src/main/java/com/flowforge/common/health/MinioHealthIndicator.java`
- `k8s/argocd/apps/minio.yaml`
- `k8s/infrastructure/minio/values.yaml`
- `libs/common/src/test/java/com/flowforge/common/client/MinioStorageClientTest.java`
- `libs/common/src/test/java/com/flowforge/common/client/MinioStorageClientIntegrationTest.java`

## Depends on

- Stage 01 (config framework, shared models)

## Produces

- AKS-deployed MinIO (ArgoCD-managed, `flowforge-infra` namespace) with all 8 default buckets
- Typed `MinioStorageClient` Spring component
- Spring Actuator health integration
- Key path conventions enforced
