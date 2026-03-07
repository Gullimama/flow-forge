# Stage 26 — MLflow Experiment Tracking (Java Client)

## Goal

Integrate **MLflow** experiment tracking into the Java pipeline using the MLflow Java client and REST API. Track all model training runs (Smile anomaly models, DJL classifiers, GNN models), log parameters/metrics/artifacts, and enable reproducibility. Spring Boot auto-configuration exposes MLflow operations as injectable services.

## Prerequisites

- Stage 12 (Smile anomaly model training)
- Stage 24 (GNN model training)
- Stage 25 (migration classifier training)

## What to build

### 26.1 MLflow configuration

```java
@ConfigurationProperties(prefix = "flowforge.mlflow")
public record MlflowProperties(
    String trackingUri,
    String experimentName,
    String artifactLocation,
    Duration timeout,
    int maxRetries
) {
    public MlflowProperties {
        if (trackingUri == null) trackingUri = "http://mlflow.flowforge-obs.svc.cluster.local:5000";
        if (experimentName == null) experimentName = "flowforge-models";
        if (timeout == null) timeout = Duration.ofSeconds(30);
        if (maxRetries <= 0) maxRetries = 3;
    }
}
```

### 26.2 MLflow REST client

```java
@Component
public class MlflowClient {

    private static final Logger log = LoggerFactory.getLogger(MlflowClient.class);

    private final RestClient restClient;
    private final MlflowProperties props;

    public MlflowClient(MlflowProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder
            .baseUrl(props.trackingUri())
            .defaultHeader("Content-Type", "application/json")
            .build();
    }

    // ── Experiment management ──────────────────────────────

    public String getOrCreateExperiment(String name) {
        // Try to get existing
        try {
            var response = restClient.get()
                .uri("/api/2.0/mlflow/experiments/get-by-name?experiment_name={name}", name)
                .retrieve()
                .body(ExperimentResponse.class);
            return response.experiment().experimentId();
        } catch (HttpClientErrorException.NotFound e) {
            // Create new
            var body = Map.of("name", name, "artifact_location", props.artifactLocation());
            var response = restClient.post()
                .uri("/api/2.0/mlflow/experiments/create")
                .body(body)
                .retrieve()
                .body(CreateExperimentResponse.class);
            return response.experimentId();
        }
    }

    // ── Run management ─────────────────────────────────────

    public MlflowRun createRun(String experimentId, String runName,
                                Map<String, String> tags) {
        var body = Map.of(
            "experiment_id", experimentId,
            "run_name", runName,
            "tags", tags.entrySet().stream()
                .map(e -> Map.of("key", e.getKey(), "value", e.getValue()))
                .toList()
        );
        var response = restClient.post()
            .uri("/api/2.0/mlflow/runs/create")
            .body(body)
            .retrieve()
            .body(RunResponse.class);
        return response.run();
    }

    public void endRun(String runId, String status) {
        restClient.post()
            .uri("/api/2.0/mlflow/runs/update")
            .body(Map.of("run_id", runId, "status", status,
                         "end_time", Instant.now().toEpochMilli()))
            .retrieve()
            .toBodilessEntity();
    }

    // ── Logging ────────────────────────────────────────────

    public void logParam(String runId, String key, String value) {
        restClient.post()
            .uri("/api/2.0/mlflow/runs/log-parameter")
            .body(Map.of("run_id", runId, "key", key, "value", value))
            .retrieve()
            .toBodilessEntity();
    }

    public void logMetric(String runId, String key, double value, long step) {
        restClient.post()
            .uri("/api/2.0/mlflow/runs/log-metric")
            .body(Map.of("run_id", runId, "key", key, "value", value,
                         "timestamp", Instant.now().toEpochMilli(), "step", step))
            .retrieve()
            .toBodilessEntity();
    }

    public void logBatch(String runId, List<MlflowMetric> metrics,
                          List<MlflowParam> params) {
        restClient.post()
            .uri("/api/2.0/mlflow/runs/log-batch")
            .body(Map.of("run_id", runId, "metrics", metrics, "params", params))
            .retrieve()
            .toBodilessEntity();
    }

    public void logArtifact(String runId, String artifactPath, byte[] data) {
        restClient.put()
            .uri("/api/2.0/mlflow-artifacts/artifacts/{path}?run_id={runId}",
                 artifactPath, runId)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(data)
            .retrieve()
            .toBodilessEntity();
    }
}

// ── DTOs ──────────────────────────────────────────────────

public record MlflowRun(RunInfo info, RunData data) {
    public String runId() { return info.runId(); }
}
public record RunInfo(String runId, String experimentId, String status,
                       long startTime, Long endTime) {}
public record RunData(List<MlflowMetric> metrics, List<MlflowParam> params) {}
public record MlflowMetric(String key, double value, long timestamp, long step) {}
public record MlflowParam(String key, String value) {}
public record ExperimentResponse(Experiment experiment) {}
public record Experiment(String experimentId, String name) {}
public record CreateExperimentResponse(String experimentId) {}
public record RunResponse(MlflowRun run) {}
```

### 26.2a MLflow AKS deployment

#### ArgoCD Application

```yaml
# k8s/argocd/apps/mlflow.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: mlflow
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "6"
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: flowforge
  source:
    repoURL: https://github.com/tesco/flow-forge.git
    targetRevision: HEAD
    path: k8s/observability/mlflow
  destination:
    server: https://kubernetes.default.svc
    namespace: flowforge-obs
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

#### Deployment

```yaml
# k8s/observability/mlflow/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mlflow
  namespace: flowforge-obs
  labels:
    app: mlflow
spec:
  replicas: 1
  selector:
    matchLabels:
      app: mlflow
  template:
    metadata:
      labels:
        app: mlflow
    spec:
      containers:
        - name: mlflow
          image: ghcr.io/mlflow/mlflow:2.17.0
          args:
            - mlflow
            - server
            - --host=0.0.0.0
            - --port=5000
            - --backend-store-uri=postgresql://$(MLFLOW_DB_USER):$(MLFLOW_DB_PASSWORD)@flowforge-pg-postgresql.flowforge-infra.svc.cluster.local:5432/mlflow
            - --artifacts-destination=s3://mlflow-artifacts
          ports:
            - containerPort: 5000
          env:
            - name: MLFLOW_S3_ENDPOINT_URL
              value: "http://flowforge-minio.flowforge-infra.svc.cluster.local:9000"
            - name: AWS_ACCESS_KEY_ID
              valueFrom:
                secretKeyRef:
                  name: mlflow-minio-credentials
                  key: access-key
            - name: AWS_SECRET_ACCESS_KEY
              valueFrom:
                secretKeyRef:
                  name: mlflow-minio-credentials
                  key: secret-key
            - name: MLFLOW_DB_USER
              valueFrom:
                secretKeyRef:
                  name: mlflow-db-credentials
                  key: username
            - name: MLFLOW_DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: mlflow-db-credentials
                  key: password
          resources:
            requests:
              cpu: "2"
              memory: 4Gi
            limits:
              cpu: "2"
              memory: 4Gi
          readinessProbe:
            httpGet:
              path: /health
              port: 5000
            initialDelaySeconds: 10
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /health
              port: 5000
            initialDelaySeconds: 20
            periodSeconds: 15
      nodeSelector:
        agentpool: cpupool
```

#### Service

```yaml
# k8s/observability/mlflow/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: mlflow
  namespace: flowforge-obs
  labels:
    app: mlflow
spec:
  type: ClusterIP
  selector:
    app: mlflow
  ports:
    - port: 5000
      targetPort: 5000
      protocol: TCP
```

> **Artifact backend:** MLflow artifacts are stored in MinIO (`flowforge-infra` namespace) under the
> `mlflow-artifacts` bucket. The `MLFLOW_S3_ENDPOINT_URL` env var points the MLflow S3-compatible
> client at the in-cluster MinIO service, so no PVC is needed for artifact storage.

#### Kustomization

```yaml
# k8s/observability/mlflow/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: flowforge-obs
resources:
  - deployment.yaml
  - service.yaml
```

### 26.3 Experiment tracker service

```java
@Service
public class ExperimentTracker {

    private static final Logger log = LoggerFactory.getLogger(ExperimentTracker.class);

    private final MlflowClient mlflow;
    private final MlflowProperties props;
    private final ObjectMapper objectMapper;

    /**
     * Track a model training run with parameters, metrics, and artifacts.
     */
    public <T> T trackRun(String runName, Map<String, String> params,
                           TrainingFunction<T> trainingFn) {
        var experimentId = mlflow.getOrCreateExperiment(props.experimentName());
        var tags = Map.of(
            "mlflow.source.name", "flowforge",
            "mlflow.runName", runName,
            "java.version", System.getProperty("java.version")
        );

        var run = mlflow.createRun(experimentId, runName, tags);
        var runId = run.runId();

        try {
            // Log all parameters
            var mlflowParams = params.entrySet().stream()
                .map(e -> new MlflowParam(e.getKey(), e.getValue()))
                .toList();
            mlflow.logBatch(runId, List.of(), mlflowParams);

            // Execute training
            var context = new TrainingContext(runId, this);
            var result = trainingFn.train(context);

            mlflow.endRun(runId, "FINISHED");
            log.info("MLflow run {} completed successfully", runId);
            return result;

        } catch (Exception e) {
            mlflow.endRun(runId, "FAILED");
            log.error("MLflow run {} failed", runId, e);
            throw new RuntimeException("Training run failed", e);
        }
    }

    /**
     * Context passed to training functions for logging metrics/artifacts.
     */
    public record TrainingContext(String runId, ExperimentTracker tracker) {

        public void logMetric(String key, double value) {
            tracker.mlflow.logMetric(runId, key, value, 0);
        }

        public void logMetric(String key, double value, long step) {
            tracker.mlflow.logMetric(runId, key, value, step);
        }

        public void logArtifact(String path, Object obj) {
            try {
                var json = tracker.objectMapper.writeValueAsBytes(obj);
                tracker.mlflow.logArtifact(runId, path, json);
            } catch (Exception e) {
                log.warn("Failed to log artifact {}", path, e);
            }
        }

        public void logModel(String path, byte[] modelBytes) {
            tracker.mlflow.logArtifact(runId, path, modelBytes);
        }
    }

    @FunctionalInterface
    public interface TrainingFunction<T> {
        T train(TrainingContext context) throws Exception;
    }
}
```

### 26.4 Integration with Smile anomaly training

```java
@Service
public class TrackedAnomalyTrainer {

    private final AnomalyDetectorModel baseModel;
    private final ExperimentTracker tracker;

    public AnomalyDetectorModel trainTracked(String snapshotId, List<LogFeatureVector> features) {
        var params = Map.of(
            "snapshot_id", snapshotId,
            "model_type", "isolation_forest",
            "num_trees", String.valueOf(baseModel.getNumTrees()),
            "subsample_size", String.valueOf(baseModel.getSubsampleSize()),
            "feature_count", String.valueOf(features.size()),
            "feature_dim", "8"
        );

        return tracker.trackRun("anomaly-" + snapshotId, params, ctx -> {
            var model = new AnomalyDetectorModel(
                baseModel.getNumTrees(), baseModel.getSubsampleSize());
            model.train(features);

            // Log training metrics
            ctx.logMetric("training_samples", features.size());

            // Log model artifact
            var modelBytes = model.serializeModel();
            ctx.logModel("models/anomaly-model.smile", modelBytes);

            return model;
        });
    }
}
```

### 26.5 Integration with GNN training

```java
@Component
public class TrackedGnnTrainer {

    private final GnnAnalysisService gnnService;
    private final ExperimentTracker tracker;

    public TrackedGnnTrainer(GnnAnalysisService gnnService, ExperimentTracker tracker) {
        this.gnnService = gnnService;
        this.tracker = tracker;
    }

    public void trainTracked(GraphData graphData, double linkThreshold) {
        tracker.trackRun("gnn-training", run -> {
            run.logParam("num_nodes", String.valueOf(graphData.numNodes()));
            run.logParam("num_edges", String.valueOf(graphData.numEdges()));
            run.logParam("link_threshold", String.valueOf(linkThreshold));

            var linkResult = gnnService.predictLinks(graphData, linkThreshold);
            run.logMetric("predicted_links", linkResult.predictedLinks().size());
            run.logMetric("link_inference_ms", linkResult.inferenceTimeMs());

            var nodeResult = gnnService.classifyNodes(graphData);
            run.logMetric("classified_nodes", nodeResult.classifications().size());
            run.logMetric("node_inference_ms", nodeResult.inferenceTimeMs());
        });
    }
}
```

### 26.6 MLflow health indicator

```java
@Component
public class MlflowHealthIndicator implements HealthIndicator {

    private final RestClient restClient;
    private final MlflowProperties props;

    public MlflowHealthIndicator(MlflowProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder.baseUrl(props.trackingUri()).build();
    }

    @Override
    public Health health() {
        try {
            restClient.get()
                .uri("/api/2.0/mlflow/experiments/search?max_results=1")
                .retrieve()
                .toBodilessEntity();
            return Health.up()
                .withDetail("trackingUri", props.trackingUri())
                .build();
        } catch (Exception e) {
            return Health.down(e)
                .withDetail("trackingUri", props.trackingUri())
                .build();
        }
    }
}
```

### 26.7 Dependencies

```kotlin
// libs/mlflow/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.databind)
}
```

Version catalog addition:
```toml
# No extra dependency needed — uses Spring's RestClient
```

## Testing & Verification Strategy

### 1. Unit Tests

All unit tests live in `libs/mlflow/src/test/java/com/flowforge/mlflow/`.

#### MlflowClientTest

Verifies all REST API interactions using **WireMock** to simulate the MLflow tracking server. No real MLflow instance is required.

```java
@WireMockTest(httpPort = 8089)
class MlflowClientTest {

    private MlflowClient client;

    @BeforeEach
    void setUp() {
        var props = new MlflowProperties(
            "http://localhost:8089", "test-experiment", "s3://artifacts",
            Duration.ofSeconds(5), 3);
        client = new MlflowClient(props, RestClient.builder());
    }

    @Test
    @DisplayName("getOrCreateExperiment returns existing experiment ID")
    void getOrCreateExperiment_existing() {
        stubFor(get(urlPathEqualTo("/api/2.0/mlflow/experiments/get-by-name"))
            .withQueryParam("experiment_name", equalTo("test-experiment"))
            .willReturn(okJson("""
                {"experiment": {"experiment_id": "42", "name": "test-experiment"}}
                """)));

        var id = client.getOrCreateExperiment("test-experiment");
        assertThat(id).isEqualTo("42");
    }

    @Test
    @DisplayName("getOrCreateExperiment creates new when 404")
    void getOrCreateExperiment_creates() {
        stubFor(get(urlPathEqualTo("/api/2.0/mlflow/experiments/get-by-name"))
            .willReturn(notFound()));
        stubFor(post(urlPathEqualTo("/api/2.0/mlflow/experiments/create"))
            .willReturn(okJson("""
                {"experiment_id": "99"}
                """)));

        var id = client.getOrCreateExperiment("new-experiment");
        assertThat(id).isEqualTo("99");
        verify(postRequestedFor(urlPathEqualTo("/api/2.0/mlflow/experiments/create"))
            .withRequestBody(matchingJsonPath("$.name", equalTo("new-experiment"))));
    }

    @Test
    @DisplayName("createRun sends tags and returns run info")
    void createRun_withTags() {
        stubFor(post(urlPathEqualTo("/api/2.0/mlflow/runs/create"))
            .willReturn(okJson("""
                {"run": {"info": {"run_id": "run-1", "experiment_id": "42",
                 "status": "RUNNING", "start_time": 1700000000000},
                 "data": {"metrics": [], "params": []}}}
                """)));

        var run = client.createRun("42", "test-run", Map.of("key", "val"));
        assertThat(run.runId()).isEqualTo("run-1");
    }

    @Test
    @DisplayName("logParam sends correct payload")
    void logParam_sendsPayload() {
        stubFor(post(urlPathEqualTo("/api/2.0/mlflow/runs/log-parameter"))
            .willReturn(ok()));

        client.logParam("run-1", "learning_rate", "0.01");

        verify(postRequestedFor(urlPathEqualTo("/api/2.0/mlflow/runs/log-parameter"))
            .withRequestBody(matchingJsonPath("$.run_id", equalTo("run-1")))
            .withRequestBody(matchingJsonPath("$.key", equalTo("learning_rate")))
            .withRequestBody(matchingJsonPath("$.value", equalTo("0.01"))));
    }

    @Test
    @DisplayName("logMetric includes timestamp and step")
    void logMetric_includesTimestampAndStep() {
        stubFor(post(urlPathEqualTo("/api/2.0/mlflow/runs/log-metric"))
            .willReturn(ok()));

        client.logMetric("run-1", "accuracy", 0.95, 10);

        verify(postRequestedFor(urlPathEqualTo("/api/2.0/mlflow/runs/log-metric"))
            .withRequestBody(matchingJsonPath("$.value", equalTo("0.95")))
            .withRequestBody(matchingJsonPath("$.step", equalTo("10"))));
    }

    @Test
    @DisplayName("logArtifact uses PUT with octet-stream content type")
    void logArtifact_putRequest() {
        stubFor(put(urlPathMatching("/api/2.0/mlflow-artifacts/artifacts/.*"))
            .willReturn(ok()));

        client.logArtifact("run-1", "model.bin", new byte[]{1, 2, 3});

        verify(putRequestedFor(urlPathMatching("/api/2.0/mlflow-artifacts/artifacts/model.bin.*"))
            .withHeader("Content-Type", equalTo("application/octet-stream")));
    }

    @Test
    @DisplayName("endRun sends FINISHED status")
    void endRun_finished() {
        stubFor(post(urlPathEqualTo("/api/2.0/mlflow/runs/update"))
            .willReturn(ok()));

        client.endRun("run-1", "FINISHED");

        verify(postRequestedFor(urlPathEqualTo("/api/2.0/mlflow/runs/update"))
            .withRequestBody(matchingJsonPath("$.status", equalTo("FINISHED"))));
    }
}
```

#### ExperimentTrackerTest

Tests the `trackRun` lifecycle: experiment creation → run creation → param logging → training execution → metric/artifact logging → run completion or failure.

```java
@ExtendWith(MockitoExtension.class)
class ExperimentTrackerTest {

    @Mock MlflowClient mlflowClient;
    @Mock ObjectMapper objectMapper;
    @InjectMocks ExperimentTracker tracker;

    private final MlflowProperties props = new MlflowProperties(
        "http://localhost:5000", "test-exp", "s3://art", Duration.ofSeconds(5), 3);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tracker, "props", props);
    }

    @Test
    @DisplayName("trackRun completes run on success")
    void trackRun_successfulTraining() {
        when(mlflowClient.getOrCreateExperiment("test-exp")).thenReturn("1");
        when(mlflowClient.createRun(eq("1"), anyString(), anyMap()))
            .thenReturn(new MlflowRun(
                new RunInfo("run-1", "1", "RUNNING", 0L, null),
                new RunData(List.of(), List.of())));

        var result = tracker.trackRun("test", Map.of("k", "v"), ctx -> {
            ctx.logMetric("acc", 0.9);
            return "done";
        });

        assertThat(result).isEqualTo("done");
        verify(mlflowClient).logBatch(eq("run-1"), eq(List.of()), anyList());
        verify(mlflowClient).endRun("run-1", "FINISHED");
    }

    @Test
    @DisplayName("trackRun marks run FAILED on exception")
    void trackRun_failedTraining() {
        when(mlflowClient.getOrCreateExperiment("test-exp")).thenReturn("1");
        when(mlflowClient.createRun(eq("1"), anyString(), anyMap()))
            .thenReturn(new MlflowRun(
                new RunInfo("run-2", "1", "RUNNING", 0L, null),
                new RunData(List.of(), List.of())));

        assertThatThrownBy(() ->
            tracker.trackRun("test", Map.of(), ctx -> {
                throw new RuntimeException("training error");
            })
        ).isInstanceOf(RuntimeException.class);

        verify(mlflowClient).endRun("run-2", "FAILED");
    }
}
```

#### TrackedAnomalyTrainerTest / TrackedGnnTrainerTest

Verify that integration wrappers delegate to the base trainer and pass correct params/metrics to the tracker.

```java
@ExtendWith(MockitoExtension.class)
class TrackedAnomalyTrainerTest {

    @Mock AnomalyDetectorModel baseModel;
    @Mock ExperimentTracker tracker;
    @InjectMocks TrackedAnomalyTrainer trackedTrainer;

    @Test
    @DisplayName("trainTracked passes snapshot ID in run name and params")
    void trainTracked_logsCorrectParams() {
        when(tracker.trackRun(eq("anomaly-snap-1"), anyMap(), any()))
            .thenAnswer(inv -> {
                ExperimentTracker.TrainingFunction<?> fn = inv.getArgument(2);
                var ctx = mock(ExperimentTracker.TrainingContext.class);
                return fn.train(ctx);
            });
        when(baseTrainer.train(anyList())).thenReturn(mockAnomalyModel());

        trackedTrainer.trainTracked("snap-1", List.of());

        verify(tracker).trackRun(eq("anomaly-snap-1"),
            argThat(p -> p.containsKey("model_type") && p.get("model_type").equals("isolation_forest")),
            any());
    }
}
```

#### MlflowHealthIndicatorTest

```java
@WireMockTest(httpPort = 8089)
class MlflowHealthIndicatorTest {

    private MlflowHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        var props = new MlflowProperties(
            "http://localhost:8089", "test", null, Duration.ofSeconds(5), 3);
        indicator = new MlflowHealthIndicator(props, RestClient.builder());
    }

    @Test
    @DisplayName("Reports UP when MLflow search endpoint responds")
    void healthUp() {
        stubFor(get(urlPathEqualTo("/api/2.0/mlflow/experiments/search"))
            .willReturn(ok()));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("Reports DOWN when MLflow is unreachable")
    void healthDown() {
        stubFor(get(urlPathEqualTo("/api/2.0/mlflow/experiments/search"))
            .willReturn(serverError()));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
```

### 2. Integration Tests

#### Full experiment lifecycle with WireMock server

Verifies the end-to-end flow from experiment creation through param/metric logging to run completion, using a stateful WireMock scenario to simulate MLflow server behavior.

```java
@SpringBootTest
@Tag("integration")
@WireMockTest(httpPort = 8089)
@TestPropertySource(properties = {
    "flowforge.mlflow.tracking-uri=http://localhost:8089",
    "flowforge.mlflow.experiment-name=integration-test"
})
class MlflowIntegrationTest {

    @Autowired ExperimentTracker tracker;

    @Test
    @DisplayName("Full experiment lifecycle: create → log → finish")
    void fullLifecycle() {
        stubFor(get(urlPathEqualTo("/api/2.0/mlflow/experiments/get-by-name"))
            .willReturn(notFound()));
        stubFor(post(urlPathEqualTo("/api/2.0/mlflow/experiments/create"))
            .willReturn(okJson("{\"experiment_id\": \"1\"}")));
        stubFor(post(urlPathEqualTo("/api/2.0/mlflow/runs/create"))
            .willReturn(okJson("""
                {"run": {"info": {"run_id": "r-1", "experiment_id": "1",
                 "status": "RUNNING", "start_time": 0},
                 "data": {"metrics": [], "params": []}}}""")));
        stubFor(post(urlPathEqualTo("/api/2.0/mlflow/runs/log-batch")).willReturn(ok()));
        stubFor(post(urlPathEqualTo("/api/2.0/mlflow/runs/log-metric")).willReturn(ok()));
        stubFor(post(urlPathEqualTo("/api/2.0/mlflow/runs/update")).willReturn(ok()));

        var result = tracker.trackRun("integration-test", Map.of("lr", "0.01"), ctx -> {
            ctx.logMetric("loss", 0.05);
            return "model-trained";
        });

        assertThat(result).isEqualTo("model-trained");
        verify(postRequestedFor(urlPathEqualTo("/api/2.0/mlflow/runs/update"))
            .withRequestBody(matchingJsonPath("$.status", equalTo("FINISHED"))));
    }
}
```

### 3. Test Fixtures & Sample Data

| Fixture file | Description |
|---|---|
| `src/test/resources/mlflow/experiment-response.json` | Sample `getExperimentByName` response with experiment ID and name |
| `src/test/resources/mlflow/create-experiment-response.json` | Sample `createExperiment` response |
| `src/test/resources/mlflow/run-response.json` | Sample `createRun` response with run info and empty data |
| `src/test/resources/mlflow/log-batch-request.json` | Sample batch request with mixed metrics and params |
| `src/test/resources/mlflow/anomaly-training-params.json` | Isolation forest training parameters fixture |
| `src/test/resources/mlflow/gnn-training-params.json` | GNN link prediction parameters fixture |

### 4. Mocking Strategy

| Dependency | Strategy | Rationale |
|---|---|---|
| MLflow REST API | **WireMock** (`@WireMockTest`) | External HTTP service — mock all REST endpoints for deterministic tests |
| `MlflowClient` | **Mockito** in `ExperimentTrackerTest` | Unit-test the tracker logic without HTTP calls |
| `AnomalyDetectorModel` | **Mockito** in `TrackedAnomalyTrainerTest` | Avoid training real Smile models in unit tests |
| `GnnAnalysisService` | **Mockito** in `TrackedGnnTrainerTest` | Avoid loading ONNX models in unit tests |
| `ObjectMapper` | **Real instance** | Jackson serialization is fast and deterministic |
| `RestClient.Builder` | **Real instance** (Spring-provided) | WireMock handles the server side; real HTTP client validates wire format |

### 5. CI/CD Considerations

- **Test tags**: Use `@Tag("unit")` for WireMock/Mockito tests, `@Tag("integration")` for full Spring context tests
- **Gradle filtering**:
  ```kotlin
  tasks.test { useJUnitPlatform { includeTags("unit") } }
  tasks.register<Test>("integrationTest") { useJUnitPlatform { includeTags("integration") } }
  ```
- **No Docker required**: All tests use WireMock — no MLflow container needed in CI
- **WireMock standalone JAR**: Not required; use the `wiremock-standalone` test dependency with `@WireMockTest` annotation
- **Test execution time**: Target < 10s for all unit tests, < 30s for integration tests
- **Parallel-safe**: WireMock tests use random ports in CI via `httpPort = 0` (override the fixed port shown in examples)

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| MLflow reachable | MlflowHealthIndicator | Health UP |
| Create experiment | getOrCreateExperiment | Returns experiment ID |
| Create run | createRun | Returns run ID + status RUNNING |
| Log params | logBatch with params | Visible in MLflow UI |
| Log metrics | logMetric | Value recorded with timestamp |
| Log batch | logBatch with metrics + params | All logged |
| Log artifact | logArtifact with JSON | Accessible in artifact store |
| End run | endRun | Status FINISHED |
| Failed run | Exception in training | Status FAILED |
| Track anomaly | trainTracked | All metrics/artifacts logged |
| Track GNN | trainTracked | Predictions logged |
| Idempotent experiment | Call twice | Same experiment ID returned |

## Files to create

- `libs/mlflow/build.gradle.kts`
- `libs/mlflow/src/main/java/com/flowforge/mlflow/config/MlflowProperties.java`
- `libs/mlflow/src/main/java/com/flowforge/mlflow/client/MlflowClient.java`
- `libs/mlflow/src/main/java/com/flowforge/mlflow/client/dto/*.java` (DTOs)
- `libs/mlflow/src/main/java/com/flowforge/mlflow/service/ExperimentTracker.java`
- `libs/mlflow/src/main/java/com/flowforge/mlflow/integration/TrackedAnomalyTrainer.java`
- `libs/mlflow/src/main/java/com/flowforge/mlflow/integration/TrackedGnnTrainer.java`
- `libs/mlflow/src/main/java/com/flowforge/mlflow/health/MlflowHealthIndicator.java`
- `libs/mlflow/src/test/java/.../ExperimentTrackerTest.java`
- `k8s/argocd/apps/mlflow.yaml`
- `k8s/observability/mlflow/deployment.yaml`
- `k8s/observability/mlflow/service.yaml`
- `k8s/observability/mlflow/kustomization.yaml`

## Depends on

- Stage 12 (anomaly model training)
- Stage 24 (GNN model inference)
- Stage 25 (migration classifier)

## Produces

- MLflow experiment tracking for all model training runs
- Parameter, metric, and artifact logging
- Training run lifecycle management (create → log → finish/fail)
- Integration wrappers for Smile + GNN training
