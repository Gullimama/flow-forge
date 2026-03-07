# Stage 28 — Argo Workflows Orchestration

## Goal

Define **Argo Workflow** DAG templates that orchestrate the full FlowForge pipeline on AKS. Each pipeline stage maps to an Argo task with proper dependency ordering, resource limits, retry policies, and artifact passing. A Java service manages workflow submission and monitoring using the Argo REST API.

## Prerequisites

- All pipeline stages 01-27 functional
- Argo Workflows installed on AKS cluster

## What to build

### 28.1 Workflow configuration

```java
@ConfigurationProperties(prefix = "flowforge.argo")
public record ArgoProperties(
    String serverUrl,
    String namespace,
    String serviceAccountName,
    String imagePullPolicy,
    Duration workflowTimeout,
    RetryPolicy defaultRetry,
    Map<String, StageResources> stageResources
) {
    public record RetryPolicy(int limit, String retryPolicy, Duration backoffDuration,
                               int backoffFactor, Duration backoffMaxDuration) {}
    public record StageResources(String cpuRequest, String cpuLimit,
                                  String memoryRequest, String memoryLimit,
                                  String gpuLimit) {}

    public ArgoProperties {
        if (serverUrl == null) serverUrl = "https://argo.flowforge.svc.cluster.local:2746";
        if (namespace == null) namespace = "flowforge";
        if (serviceAccountName == null) serviceAccountName = "flowforge-workflow";
        if (imagePullPolicy == null) imagePullPolicy = "IfNotPresent";
        if (workflowTimeout == null) workflowTimeout = Duration.ofHours(6);
        if (defaultRetry == null) defaultRetry = new RetryPolicy(
            3, "Always", Duration.ofSeconds(30), 2, Duration.ofMinutes(10));
    }
}
```

### 28.2 Full pipeline DAG workflow YAML

```yaml
# k8s/argo/flowforge-pipeline.yaml
apiVersion: argoproj.io/v1alpha1
kind: Workflow
metadata:
  generateName: flowforge-pipeline-
  namespace: flowforge
  labels:
    app: flowforge
    component: pipeline
spec:
  entrypoint: flowforge-dag
  serviceAccountName: flowforge-workflow
  activeDeadlineSeconds: 21600  # 6 hours
  ttlStrategy:
    secondsAfterCompletion: 86400
    secondsAfterFailure: 172800
  arguments:
    parameters:
      - name: snapshot-id
      - name: repo-urls
        value: '[]'
      - name: log-time-range
        value: '24h'
      - name: run-gnn
        value: 'true'

  templates:
    - name: flowforge-dag
      dag:
        tasks:
          # ── Data Ingestion Layer ────────────────────────
          - name: clone-repos
            template: stage-runner
            arguments:
              parameters:
                - name: stage
                  value: "clone-repos"
                - name: args
                  value: '{"repoUrls": "{{workflow.parameters.repo-urls}}", "snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          - name: fetch-logs
            template: stage-runner
            arguments:
              parameters:
                - name: stage
                  value: "fetch-logs"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}", "timeRange": "{{workflow.parameters.log-time-range}}"}'

          - name: fetch-manifests
            template: stage-runner
            arguments:
              parameters:
                - name: stage
                  value: "fetch-manifests"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          # ── Parsing Layer ───────────────────────────────
          - name: parse-code
            template: stage-runner
            dependencies: [clone-repos]
            arguments:
              parameters:
                - name: stage
                  value: "parse-code"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          - name: parse-logs
            template: stage-runner
            dependencies: [fetch-logs]
            arguments:
              parameters:
                - name: stage
                  value: "parse-logs"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          - name: parse-topology
            template: stage-runner
            dependencies: [fetch-manifests]
            arguments:
              parameters:
                - name: stage
                  value: "parse-topology"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          # ── Indexing Layer ──────────────────────────────
          - name: index-opensearch
            template: stage-runner
            dependencies: [parse-code, parse-logs]
            arguments:
              parameters:
                - name: stage
                  value: "index-opensearch"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          - name: build-knowledge-graph
            template: stage-runner
            dependencies: [parse-code, parse-topology]
            arguments:
              parameters:
                - name: stage
                  value: "build-knowledge-graph"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          # ── Analytics Layer ─────────────────────────────
          - name: detect-anomalies
            template: stage-runner
            dependencies: [parse-logs]
            arguments:
              parameters:
                - name: stage
                  value: "detect-anomalies"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          - name: mine-sequences
            template: stage-runner
            dependencies: [parse-logs, build-knowledge-graph]
            arguments:
              parameters:
                - name: stage
                  value: "mine-sequences"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          # ── Embedding Layer ─────────────────────────────
          - name: embed-code
            template: gpu-stage-runner
            dependencies: [parse-code]
            arguments:
              parameters:
                - name: stage
                  value: "embed-code"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          - name: embed-logs
            template: gpu-stage-runner
            dependencies: [parse-logs]
            arguments:
              parameters:
                - name: stage
                  value: "embed-logs"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          # ── GNN Layer (optional) ────────────────────────
          - name: gnn-analysis
            template: gpu-stage-runner
            dependencies: [build-knowledge-graph, embed-code]
            when: "'{{workflow.parameters.run-gnn}}' == 'true'"
            arguments:
              parameters:
                - name: stage
                  value: "gnn-analysis"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          - name: classify-migration
            template: stage-runner
            dependencies: [parse-code, embed-code]
            arguments:
              parameters:
                - name: stage
                  value: "classify-migration"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          # ── Retrieval Layer ─────────────────────────────
          - name: build-candidates
            template: stage-runner
            dependencies:
              - index-opensearch
              - build-knowledge-graph
              - detect-anomalies
              - mine-sequences
              - embed-code
              - embed-logs
              - classify-migration
            arguments:
              parameters:
                - name: stage
                  value: "build-candidates"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          # ── Synthesis Layer ─────────────────────────────
          - name: synthesize
            template: stage-runner
            dependencies: [build-candidates]
            arguments:
              parameters:
                - name: stage
                  value: "synthesize"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          # ── Output Layer ────────────────────────────────
          - name: publish-output
            template: stage-runner
            dependencies: [synthesize]
            arguments:
              parameters:
                - name: stage
                  value: "publish-output"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

          # ── Evaluation Layer ────────────────────────────
          - name: evaluate
            template: stage-runner
            dependencies: [publish-output]
            arguments:
              parameters:
                - name: stage
                  value: "evaluate"
                - name: args
                  value: '{"snapshotId": "{{workflow.parameters.snapshot-id}}"}'

    # ── Template: standard stage runner ───────────────────
    # Uses the CPU-only image (no GPU libraries, ~400MB smaller)
    - name: stage-runner
      inputs:
        parameters:
          - name: stage
          - name: args
      retryStrategy:
        limit: 3
        retryPolicy: "Always"
        backoff:
          duration: "30s"
          factor: 2
          maxDuration: "10m"
      container:
        image: flowforge-pipeline-cpu:latest
        imagePullPolicy: IfNotPresent
        command: ["java", "-jar", "/app/pipeline.jar"]
        args:
          - "--stage={{inputs.parameters.stage}}"
          - "--args={{inputs.parameters.args}}"
        env:
          - name: JAVA_OPTS
            value: "-XX:+UseZGC -Xmx4g"
          - name: SPRING_PROFILES_ACTIVE
            value: "aks"
        resources:
          requests:
            cpu: "1"
            memory: "4Gi"
          limits:
            cpu: "4"
            memory: "8Gi"

    # ── Template: GPU-enabled stage runner ────────────────
    # Uses the GPU image (includes DJL ONNX Runtime + CUDA libraries)
    - name: gpu-stage-runner
      inputs:
        parameters:
          - name: stage
          - name: args
      retryStrategy:
        limit: 2
        retryPolicy: "Always"
        backoff:
          duration: "60s"
          factor: 2
      container:
        image: flowforge-pipeline-gpu:latest
        imagePullPolicy: IfNotPresent
        command: ["java", "-jar", "/app/pipeline.jar"]
        args:
          - "--stage={{inputs.parameters.stage}}"
          - "--args={{inputs.parameters.args}}"
        env:
          - name: JAVA_OPTS
            value: "-XX:+UseZGC -Xmx8g"
          - name: SPRING_PROFILES_ACTIVE
            value: "aks,gpu"
        resources:
          requests:
            cpu: "2"
            memory: "8Gi"
            nvidia.com/gpu: "1"
          limits:
            cpu: "8"
            memory: "16Gi"
            nvidia.com/gpu: "1"
      nodeSelector:
        agentpool: gpupool
      tolerations:
        - key: "nvidia.com/gpu"
          operator: "Exists"
          effect: "NoSchedule"
```

### 28.3 Argo client service

```java
@Service
public class ArgoWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(ArgoWorkflowService.class);

    private final RestClient restClient;
    private final ArgoProperties props;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ArgoWorkflowService(ArgoProperties props, RestClient.Builder builder,
                                ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.restClient = builder
            .baseUrl(props.serverUrl())
            .defaultHeader("Content-Type", "application/json")
            .build();
    }

    /**
     * Submit a new pipeline workflow.
     */
    public WorkflowStatus submitPipeline(PipelineRequest request) {
        var workflow = loadWorkflowTemplate("flowforge-pipeline.yaml");
        setParameters(workflow, Map.of(
            "snapshot-id", request.snapshotId().toString(),
            "repo-urls", objectMapper.writeValueAsString(request.repoUrls()),
            "log-time-range", request.logTimeRange(),
            "run-gnn", String.valueOf(request.runGnn())
        ));

        var response = restClient.post()
            .uri("/api/v1/workflows/{ns}", props.namespace())
            .body(Map.of("workflow", workflow))
            .retrieve()
            .body(WorkflowResponse.class);

        meterRegistry.counter("flowforge.argo.workflow.submitted").increment();
        log.info("Submitted workflow: {}", response.metadata().name());

        return new WorkflowStatus(
            response.metadata().name(),
            response.status().phase(),
            response.metadata().creationTimestamp()
        );
    }

    /**
     * Get workflow status by name.
     */
    public WorkflowStatus getStatus(String workflowName) {
        var response = restClient.get()
            .uri("/api/v1/workflows/{ns}/{name}", props.namespace(), workflowName)
            .retrieve()
            .body(WorkflowResponse.class);

        return new WorkflowStatus(
            response.metadata().name(),
            response.status().phase(),
            response.metadata().creationTimestamp()
        );
    }

    /**
     * Get node-level status for all DAG tasks.
     */
    public List<TaskStatus> getTaskStatuses(String workflowName) {
        var response = restClient.get()
            .uri("/api/v1/workflows/{ns}/{name}", props.namespace(), workflowName)
            .retrieve()
            .body(WorkflowResponse.class);

        return response.status().nodes().values().stream()
            .filter(n -> "Pod".equals(n.type()))
            .map(n -> new TaskStatus(
                n.displayName(), n.phase(),
                n.startedAt(), n.finishedAt(),
                n.message()
            ))
            .toList();
    }

    /**
     * Wait for workflow completion with polling.
     */
    public WorkflowStatus waitForCompletion(String workflowName, Duration timeout) {
        var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            var status = getStatus(workflowName);
            if (isTerminal(status.phase())) {
                meterRegistry.counter("flowforge.argo.workflow.completed",
                    "phase", status.phase()).increment();
                return status;
            }
            try { Thread.sleep(10_000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for workflow", e);
            }
        }
        throw new TimeoutException("Workflow did not complete within " + timeout);
    }

    private boolean isTerminal(String phase) {
        return "Succeeded".equals(phase) || "Failed".equals(phase) || "Error".equals(phase);
    }
}

// ── DTOs ──────────────────────────────────────────────────

public record PipelineRequest(
    UUID snapshotId,
    List<String> repoUrls,
    String logTimeRange,
    boolean runGnn
) {}

public record WorkflowStatus(String name, String phase, String startedAt) {}

public record TaskStatus(String name, String phase, String startedAt,
                          String finishedAt, String message) {}
```

### 28.4 Stage runner (common entry point)

```java
@SpringBootApplication
public class PipelineRunner implements CommandLineRunner {

    private final Map<String, PipelineStage> stages;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) throws Exception {
        var stageName = extractArg(args, "--stage");
        var stageArgs = extractArg(args, "--args");

        var stage = stages.get(stageName);
        if (stage == null) {
            throw new IllegalArgumentException("Unknown stage: " + stageName);
        }

        var argsMap = objectMapper.readValue(stageArgs, Map.class);
        stage.execute(argsMap);
    }
}

/**
 * Common interface for all pipeline stages invoked by Argo.
 */
public interface PipelineStage {
    String name();
    void execute(Map<String, Object> args) throws Exception;
}

/**
 * Example: code parsing stage registration.
 */
@Component("parse-code")
public class CodeParsingStage implements PipelineStage {

    private final CodeParsingService codeParser;

    @Override
    public String name() { return "parse-code"; }

    @Override
    public void execute(Map<String, Object> args) throws Exception {
        var snapshotId = UUID.fromString((String) args.get("snapshotId"));
        codeParser.parseSnapshot(snapshotId);
    }
}
```

### 28.5 REST API for workflow management

```java
@RestController
@RequestMapping("/api/v1/pipelines")
public class PipelineController {

    private final ArgoWorkflowService argoService;

    @PostMapping
    public ResponseEntity<WorkflowStatus> submitPipeline(
            @RequestBody @Valid PipelineRequest request) {
        var status = argoService.submitPipeline(request);
        return ResponseEntity.accepted().body(status);
    }

    @GetMapping("/{name}")
    public WorkflowStatus getStatus(@PathVariable String name) {
        return argoService.getStatus(name);
    }

    @GetMapping("/{name}/tasks")
    public List<TaskStatus> getTaskStatuses(@PathVariable String name) {
        return argoService.getTaskStatuses(name);
    }
}
```

### 28.6 Dependencies

```kotlin
// services/orchestrator/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
}
```

### 28.7 Container image build strategy

The workflow uses **two separate images** to avoid shipping GPU libraries
to CPU-only stages (saves ~400 MB per pod and reduces CVE surface):

| Image | Contents | Used by |
|---|---|---|
| `flowforge-pipeline-cpu` | JRE 25, pipeline JAR, no DJL CUDA | `stage-runner` template |
| `flowforge-pipeline-gpu` | JRE 25, pipeline JAR, DJL + ONNX Runtime CUDA | `gpu-stage-runner` template |

Both images are built from the same Gradle project with different Jib
profiles:

```bash
# CPU image (default)
./gradlew :services:pipeline:jibDockerBuild \
  -Djib.to.image=flowforge-pipeline-cpu

# GPU image (includes DJL CUDA extras)
./gradlew :services:pipeline:jibDockerBuild \
  -Djib.to.image=flowforge-pipeline-gpu \
  -PincludeGpuDeps=true
```

The `includeGpuDeps` flag controls whether `ai.djl:djl-onnxruntime-gpu`
and CUDA native libraries are included. In `build.gradle.kts`:

```kotlin
if (project.hasProperty("includeGpuDeps")) {
    runtimeOnly(libs.djl.onnxruntime.gpu)
}
```

### 28.8 Argo Workflows AKS deployment via ArgoCD

#### ArgoCD Application (multi-source: Helm chart + Git values)

```yaml
# k8s/argocd/apps/argo-workflows.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: argo-workflows
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "3"
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: flowforge
  sources:
    - repoURL: https://argoproj.github.io/argo-helm
      chart: argo-workflows
      targetRevision: 0.42.*
      helm:
        valueFiles:
          - $values/k8s/infrastructure/argo-workflows/values.yaml
    - repoURL: https://github.com/tesco/flow-forge.git
      targetRevision: HEAD
      ref: values
  destination:
    server: https://kubernetes.default.svc
    namespace: argo
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - ServerSideApply=true
```

#### Helm values

```yaml
# k8s/infrastructure/argo-workflows/values.yaml
controller:
  resources:
    requests:
      cpu: "500m"
      memory: 512Mi
    limits:
      cpu: "1"
      memory: 1Gi
  workflowDefaults:
    spec:
      serviceAccountName: flowforge-workflow
      nodeSelector:
        agentpool: cpupool
      ttlStrategy:
        secondsAfterCompletion: 86400
        secondsAfterFailure: 172800

server:
  authMode: server
  resources:
    requests:
      cpu: "250m"
      memory: 256Mi
    limits:
      cpu: "500m"
      memory: 512Mi

executor:
  image:
    registry: quay.io
    repository: argoproj/argoexec
    tag: v3.5.0

artifactRepository:
  s3:
    bucket: argo-artifacts
    endpoint: flowforge-minio.flowforge-infra.svc.cluster.local:9000
    insecure: true
    accessKeySecret:
      name: argo-minio-credentials
      key: access-key
    secretKeySecret:
      name: argo-minio-credentials
      key: secret-key

workflow:
  serviceAccount:
    create: true
    name: flowforge-workflow
  rbac:
    create: true
```

## Testing & Verification Strategy

### 1. Unit Tests

All unit tests live in `services/orchestrator/src/test/java/com/flowforge/orchestrator/`.

#### ArgoWorkflowServiceTest

Verifies all Argo REST API interactions using **WireMock** to simulate the Argo server. Tests workflow submission, status polling, task status extraction, and terminal-state detection.

```java
@WireMockTest(httpPort = 8089)
class ArgoWorkflowServiceTest {

    private ArgoWorkflowService service;
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        var props = new ArgoProperties(
            "http://localhost:8089", "flowforge", "flowforge-workflow",
            "IfNotPresent", Duration.ofHours(6), null, Map.of());
        service = new ArgoWorkflowService(
            props, RestClient.builder(), new ObjectMapper(), meterRegistry);
    }

    @Test
    @DisplayName("submitPipeline sends workflow and returns status")
    void submitPipeline_returnsStatus() {
        stubFor(post(urlPathEqualTo("/api/v1/workflows/flowforge"))
            .willReturn(okJson("""
                {"metadata": {"name": "flowforge-pipeline-abc", "creationTimestamp": "2025-01-01T00:00:00Z"},
                 "status": {"phase": "Running"}}
                """)));

        var request = new PipelineRequest(
            UUID.randomUUID(), List.of("https://github.com/org/repo"), "24h", true);
        var status = service.submitPipeline(request);

        assertThat(status.name()).isEqualTo("flowforge-pipeline-abc");
        assertThat(status.phase()).isEqualTo("Running");
        assertThat(meterRegistry.counter("flowforge.argo.workflow.submitted").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("getStatus returns current workflow phase")
    void getStatus_returnsPhase() {
        stubFor(get(urlPathEqualTo("/api/v1/workflows/flowforge/wf-1"))
            .willReturn(okJson("""
                {"metadata": {"name": "wf-1", "creationTimestamp": "2025-01-01T00:00:00Z"},
                 "status": {"phase": "Succeeded"}}
                """)));

        var status = service.getStatus("wf-1");
        assertThat(status.phase()).isEqualTo("Succeeded");
    }

    @Test
    @DisplayName("getTaskStatuses filters Pod-type nodes")
    void getTaskStatuses_filtersPods() {
        stubFor(get(urlPathEqualTo("/api/v1/workflows/flowforge/wf-1"))
            .willReturn(okJson("""
                {"metadata": {"name": "wf-1", "creationTimestamp": "2025-01-01T00:00:00Z"},
                 "status": {"phase": "Running", "nodes": {
                   "node-1": {"displayName": "clone-repos", "phase": "Succeeded",
                              "type": "Pod", "startedAt": "T0", "finishedAt": "T1", "message": ""},
                   "node-2": {"displayName": "flowforge-dag", "phase": "Running",
                              "type": "DAG", "startedAt": "T0", "finishedAt": null, "message": ""},
                   "node-3": {"displayName": "parse-code", "phase": "Running",
                              "type": "Pod", "startedAt": "T0", "finishedAt": null, "message": ""}
                 }}}
                """)));

        var tasks = service.getTaskStatuses("wf-1");

        assertThat(tasks).hasSize(2);
        assertThat(tasks).extracting(TaskStatus::name)
            .containsExactlyInAnyOrder("clone-repos", "parse-code");
    }

    @Test
    @DisplayName("waitForCompletion returns when phase is terminal")
    void waitForCompletion_returnsOnTerminal() {
        stubFor(get(urlPathEqualTo("/api/v1/workflows/flowforge/wf-1"))
            .willReturn(okJson("""
                {"metadata": {"name": "wf-1", "creationTimestamp": "2025-01-01T00:00:00Z"},
                 "status": {"phase": "Succeeded"}}
                """)));

        var status = service.waitForCompletion("wf-1", Duration.ofSeconds(5));
        assertThat(status.phase()).isEqualTo("Succeeded");
    }

    @Test
    @DisplayName("isTerminal recognizes Succeeded, Failed, Error")
    void isTerminal_recognizesAllStates() {
        for (String phase : List.of("Succeeded", "Failed", "Error")) {
            stubFor(get(urlPathEqualTo("/api/v1/workflows/flowforge/wf-term"))
                .willReturn(okJson("""
                    {"metadata": {"name": "wf-term", "creationTimestamp": "T"},
                     "status": {"phase": "%s"}}
                    """.formatted(phase))));

            var status = service.waitForCompletion("wf-term", Duration.ofSeconds(2));
            assertThat(status.phase()).isEqualTo(phase);
        }
    }
}
```

#### PipelineRunnerTest

Tests command-line argument parsing and stage dispatch.

```java
@ExtendWith(MockitoExtension.class)
class PipelineRunnerTest {

    @Mock ObjectMapper objectMapper;
    @InjectMocks PipelineRunner runner;

    @Test
    @DisplayName("Dispatches to correct PipelineStage bean by name")
    void dispatches_correctStage() throws Exception {
        var mockStage = mock(PipelineStage.class);
        when(mockStage.name()).thenReturn("parse-code");

        ReflectionTestUtils.setField(runner, "stages",
            Map.of("parse-code", mockStage));
        when(objectMapper.readValue(anyString(), eq(Map.class)))
            .thenReturn(Map.of("snapshotId", "abc-123"));

        runner.run("--stage=parse-code", "--args={\"snapshotId\":\"abc-123\"}");

        verify(mockStage).execute(argThat(args ->
            "abc-123".equals(args.get("snapshotId"))));
    }

    @Test
    @DisplayName("Throws IllegalArgumentException for unknown stage")
    void unknownStage_throwsException() {
        ReflectionTestUtils.setField(runner, "stages", Map.of());

        assertThatThrownBy(() ->
            runner.run("--stage=nonexistent", "--args={}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown stage: nonexistent");
    }
}
```

#### PipelineControllerTest

Tests REST endpoints with MockMvc.

```java
@WebMvcTest(PipelineController.class)
class PipelineControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ArgoWorkflowService argoService;

    @Test
    @DisplayName("POST /api/v1/pipelines returns 202 Accepted")
    void submitPipeline_returns202() throws Exception {
        when(argoService.submitPipeline(any())).thenReturn(
            new WorkflowStatus("wf-1", "Pending", "2025-01-01T00:00:00Z"));

        mockMvc.perform(post("/api/v1/pipelines")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"snapshotId": "550e8400-e29b-41d4-a716-446655440000",
                     "repoUrls": ["https://github.com/org/repo"],
                     "logTimeRange": "24h", "runGnn": true}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.name").value("wf-1"));
    }

    @Test
    @DisplayName("GET /api/v1/pipelines/{name} returns workflow status")
    void getStatus_returnsStatus() throws Exception {
        when(argoService.getStatus("wf-1")).thenReturn(
            new WorkflowStatus("wf-1", "Succeeded", "2025-01-01T00:00:00Z"));

        mockMvc.perform(get("/api/v1/pipelines/wf-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.phase").value("Succeeded"));
    }

    @Test
    @DisplayName("GET /api/v1/pipelines/{name}/tasks returns task list")
    void getTaskStatuses_returnsTasks() throws Exception {
        when(argoService.getTaskStatuses("wf-1")).thenReturn(List.of(
            new TaskStatus("clone-repos", "Succeeded", "T0", "T1", ""),
            new TaskStatus("parse-code", "Running", "T1", null, "")));

        mockMvc.perform(get("/api/v1/pipelines/wf-1/tasks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].name").value("clone-repos"));
    }
}
```

#### Workflow YAML Validation Test

Validates the Argo workflow YAML for structural correctness and DAG dependency ordering.

```java
@Tag("unit")
class WorkflowYamlValidationTest {

    private Map<String, Object> workflow;

    @BeforeEach
    void setUp() throws Exception {
        var yaml = new ObjectMapper(new YAMLFactory());
        workflow = yaml.readValue(
            Path.of("k8s/argo/flowforge-pipeline.yaml").toFile(), Map.class);
    }

    @Test
    @DisplayName("Workflow YAML is valid and parseable")
    void yamlParseable() {
        assertThat(workflow).containsKey("apiVersion");
        assertThat(workflow.get("apiVersion")).isEqualTo("argoproj.io/v1alpha1");
    }

    @Test
    @DisplayName("DAG contains all expected tasks")
    @SuppressWarnings("unchecked")
    void dagContainsAllTasks() {
        var templates = (List<Map<String, Object>>) getPath(workflow, "spec.templates");
        var dagTemplate = templates.stream()
            .filter(t -> "flowforge-dag".equals(t.get("name")))
            .findFirst().orElseThrow();
        var tasks = (List<Map<String, Object>>) getPath(dagTemplate, "dag.tasks");
        var taskNames = tasks.stream().map(t -> (String) t.get("name")).toList();

        assertThat(taskNames).contains(
            "clone-repos", "fetch-logs", "parse-code", "parse-logs",
            "index-opensearch", "build-knowledge-graph", "detect-anomalies",
            "embed-code", "embed-logs", "build-candidates", "synthesize",
            "publish-output", "evaluate");
    }

    @Test
    @DisplayName("parse-code depends on clone-repos")
    @SuppressWarnings("unchecked")
    void parseCodeDependsOnCloneRepos() {
        var tasks = getDagTasks();
        var parseCode = tasks.stream()
            .filter(t -> "parse-code".equals(t.get("name")))
            .findFirst().orElseThrow();
        var deps = (List<String>) parseCode.get("dependencies");

        assertThat(deps).contains("clone-repos");
    }

    @Test
    @DisplayName("build-candidates depends on all upstream indexing/analytics stages")
    @SuppressWarnings("unchecked")
    void buildCandidates_allDependencies() {
        var tasks = getDagTasks();
        var buildCandidates = tasks.stream()
            .filter(t -> "build-candidates".equals(t.get("name")))
            .findFirst().orElseThrow();
        var deps = (List<String>) buildCandidates.get("dependencies");

        assertThat(deps).containsExactlyInAnyOrder(
            "index-opensearch", "build-knowledge-graph", "detect-anomalies",
            "mine-sequences", "embed-code", "embed-logs", "classify-migration");
    }

    @Test
    @DisplayName("GPU stages use gpu-stage-runner template")
    @SuppressWarnings("unchecked")
    void gpuStages_useGpuTemplate() {
        var tasks = getDagTasks();
        var gpuTasks = List.of("embed-code", "embed-logs", "gnn-analysis");

        for (String taskName : gpuTasks) {
            var task = tasks.stream()
                .filter(t -> taskName.equals(t.get("name")))
                .findFirst().orElseThrow();
            assertThat(task.get("template")).isEqualTo("gpu-stage-runner");
        }
    }
}
```

### 2. Integration Tests

#### ArgoWorkflowService integration with WireMock

Tests the full submit → poll → complete lifecycle using stateful WireMock scenarios that transition workflow phases.

```java
@SpringBootTest
@Tag("integration")
@WireMockTest(httpPort = 8089)
@TestPropertySource(properties = {
    "flowforge.argo.server-url=http://localhost:8089",
    "flowforge.argo.namespace=flowforge"
})
class ArgoWorkflowIntegrationTest {

    @Autowired ArgoWorkflowService argoService;

    @Test
    @DisplayName("Submit and poll until workflow succeeds")
    void submitAndPoll() {
        stubFor(post(urlPathEqualTo("/api/v1/workflows/flowforge"))
            .willReturn(okJson("""
                {"metadata": {"name": "wf-integ", "creationTimestamp": "T0"},
                 "status": {"phase": "Running"}}""")));

        stubFor(get(urlPathEqualTo("/api/v1/workflows/flowforge/wf-integ"))
            .inScenario("workflow-lifecycle")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson("""
                {"metadata": {"name": "wf-integ", "creationTimestamp": "T0"},
                 "status": {"phase": "Running"}}"""))
            .willSetStateTo("completed"));

        stubFor(get(urlPathEqualTo("/api/v1/workflows/flowforge/wf-integ"))
            .inScenario("workflow-lifecycle")
            .whenScenarioStateIs("completed")
            .willReturn(okJson("""
                {"metadata": {"name": "wf-integ", "creationTimestamp": "T0"},
                 "status": {"phase": "Succeeded"}}""")));

        var submitted = argoService.submitPipeline(new PipelineRequest(
            UUID.randomUUID(), List.of(), "24h", false));
        assertThat(submitted.phase()).isEqualTo("Running");

        var completed = argoService.waitForCompletion("wf-integ", Duration.ofSeconds(30));
        assertThat(completed.phase()).isEqualTo("Succeeded");
    }
}
```

#### YAML lint validation via Argo CLI (optional, requires Docker)

```java
@Tag("docker")
@Testcontainers
class ArgoYamlLintTest {

    @Container
    static GenericContainer<?> argoLint = new GenericContainer<>("argoproj/argocli:v3.5.0")
        .withCommand("lint", "/workflow.yaml")
        .withFileSystemBind("k8s/argo/flowforge-pipeline.yaml",
            "/workflow.yaml", BindMode.READ_ONLY);

    @Test
    @DisplayName("Argo CLI validates workflow YAML")
    void argoLintPasses() {
        argoLint.start();
        assertThat(argoLint.getLogs()).doesNotContain("Error");
    }
}
```

### 3. Test Fixtures & Sample Data

| Fixture file | Description |
|---|---|
| `src/test/resources/argo/submit-response.json` | Sample Argo workflow submission response with metadata and initial status |
| `src/test/resources/argo/status-running.json` | Workflow status response in `Running` phase with active node map |
| `src/test/resources/argo/status-succeeded.json` | Workflow status response in `Succeeded` phase with all nodes completed |
| `src/test/resources/argo/status-failed.json` | Workflow status with `Failed` phase and error message on a specific node |
| `src/test/resources/argo/task-statuses.json` | Full node map with mixed Pod and DAG types for filtering tests |
| `k8s/argo/flowforge-pipeline.yaml` | The actual workflow YAML used in YAML validation tests |

### 4. Mocking Strategy

| Dependency | Strategy | Rationale |
|---|---|---|
| Argo REST API | **WireMock** (`@WireMockTest`) | External HTTP service — simulate all workflow API endpoints |
| `ArgoWorkflowService` | **Mockito** (`@MockitoBean`) in controller tests | Isolate REST layer from Argo client logic |
| `PipelineStage` beans | **Mockito** (`mock()`) in `PipelineRunnerTest` | Avoid executing real pipeline stages |
| `ObjectMapper` | **Real instance** (Jackson) | YAML/JSON parsing must work correctly for workflow templates |
| `MeterRegistry` | **`SimpleMeterRegistry`** | In-memory registry for verifying counter increments |
| Argo CLI | **Testcontainers** (optional) | Validate YAML with the real `argo lint` tool when Docker is available |

### 5. CI/CD Considerations

- **Test tags**: `@Tag("unit")` for WireMock/Mockito tests, `@Tag("integration")` for Spring context tests, `@Tag("docker")` for Testcontainers-based lint tests
- **Gradle task separation**:
  ```kotlin
  tasks.test { useJUnitPlatform { includeTags("unit") } }
  tasks.register<Test>("integrationTest") { useJUnitPlatform { includeTags("integration") } }
  tasks.register<Test>("dockerTest") { useJUnitPlatform { includeTags("docker") } }
  ```
- **Docker requirement**: Only `@Tag("docker")` tests require Docker (Argo CLI lint); unit and integration tests run without Docker
- **YAML validation in CI**: Run `argo lint` as a separate CI step using the `argoproj/argocli` Docker image, or use the `@Tag("docker")` Testcontainers test
- **Workflow template changes**: Any change to `k8s/argo/flowforge-pipeline.yaml` should trigger the DAG dependency validation tests to catch broken task ordering
- **Test execution time**: Target < 10s for unit tests, < 30s for integration tests, < 60s for Docker-based lint tests

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| YAML valid | `argo lint flowforge-pipeline.yaml` | No errors |
| DAG dependencies | Visual in Argo UI | Correct ordering |
| Submit workflow | POST /api/v1/pipelines | Returns 202 + workflow name |
| Get status | GET /api/v1/pipelines/{name} | Returns current phase |
| Task statuses | GET /api/v1/pipelines/{name}/tasks | All tasks listed |
| Retry policy | Kill a pod mid-run | Auto-retries up to limit |
| GPU toleration | embed-code task | Scheduled on GPU node |
| Timeout | Set short deadline | Workflow fails on timeout |
| GNN conditional | run-gnn=false | gnn-analysis task skipped |
| Stage runner | Run with --stage=parse-code | Correct stage executes |
| Parallel tasks | clone-repos, fetch-logs, fetch-manifests | Run concurrently |
| End-to-end | Submit full pipeline | All tasks succeed in order |
| CPU image size | `docker images flowforge-pipeline-cpu` | < 500 MB |
| GPU image size | `docker images flowforge-pipeline-gpu` | < 900 MB |
| CPU image no GPU | `docker run flowforge-pipeline-cpu java -jar /app/pipeline.jar --stage=parse-code` | Works without CUDA |

## Files to create

- `k8s/argo/flowforge-pipeline.yaml`
- `k8s/argo/flowforge-serviceaccount.yaml`
- `services/orchestrator/build.gradle.kts`
- `services/orchestrator/src/main/java/com/flowforge/orchestrator/config/ArgoProperties.java`
- `services/orchestrator/src/main/java/com/flowforge/orchestrator/client/ArgoWorkflowService.java`
- `services/orchestrator/src/main/java/com/flowforge/orchestrator/stage/PipelineStage.java`
- `services/orchestrator/src/main/java/com/flowforge/orchestrator/stage/PipelineRunner.java`
- `services/orchestrator/src/main/java/com/flowforge/orchestrator/controller/PipelineController.java`
- `services/orchestrator/src/test/java/.../ArgoWorkflowServiceTest.java`
- `k8s/argocd/apps/argo-workflows.yaml`
- `k8s/infrastructure/argo-workflows/values.yaml`

## Depends on

- All pipeline stages (01-27) registered as PipelineStage beans
- Argo Workflows controller running on AKS

## Produces

- Argo DAG workflow template for full pipeline
- REST API for pipeline submission and monitoring
- Common PipelineStage interface for stage registration
- Retry and timeout policies for reliability
- GPU-aware scheduling for embedding/GNN stages
