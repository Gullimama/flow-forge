# Stage 04 — FlowForge Control Plane API

## Goal

Build the Spring Boot REST API control plane that serves as the single entry point for triggering all FlowForge operations — snapshots, log ingestion, research runs, and status retrieval.

## Prerequisites

- Stage 01 completed (project structure)
- Stage 02 completed (PostgreSQL metadata repository)
- Stage 03 completed (MinIO storage)

## What to build

### 4.1 Spring Boot application

```java
@SpringBootApplication
@EnableConfigurationProperties(FlowForgeProperties.class)
public class FlowForgeApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlowForgeApiApplication.class, args);
    }
}
```

`application.yml`:
```yaml
server:
  port: 8080
spring:
  threads:
    virtual:
      enabled: true    # Java 25 virtual threads for all request handling
  datasource:
    url: ${FLOWFORGE_POSTGRES_URL}
    username: ${FLOWFORGE_POSTGRES_USERNAME}
    password: ${FLOWFORGE_POSTGRES_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
```

### 4.1a AKS deployment

#### ArgoCD Application

```yaml
# k8s/argocd/apps/flowforge-api.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: flowforge-api
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "7"
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: flowforge
  source:
    repoURL: https://github.com/tesco/flow-forge.git
    targetRevision: HEAD
    path: k8s/app/flowforge-api
  destination:
    server: https://kubernetes.default.svc
    namespace: flowforge
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

#### Deployment

```yaml
# k8s/app/flowforge-api/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: flowforge-api
  namespace: flowforge
  labels:
    app: flowforge-api
spec:
  replicas: 2
  selector:
    matchLabels:
      app: flowforge-api
  template:
    metadata:
      labels:
        app: flowforge-api
      annotations:
        dapr.io/enabled: "true"
        dapr.io/app-id: "flowforge-api"
        dapr.io/app-port: "8080"
        dapr.io/metrics-port: "9090"
        dapr.io/log-as-json: "true"
    spec:
      containers:
        - name: flowforge-api
          image: flowforgeacr.azurecr.io/flowforge-api:latest
          ports:
            - containerPort: 8080
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "aks"
            - name: JAVA_TOOL_OPTIONS
              value: "-javaagent:/app/opentelemetry-javaagent.jar"
          envFrom:
            - configMapRef:
                name: flowforge-api-config
            - secretRef:
                name: flowforge-api-secrets
          resources:
            requests:
              cpu: "2"
              memory: 4Gi
            limits:
              cpu: "4"
              memory: 8Gi
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 15
      nodeSelector:
        agentpool: cpupool
```

#### Service

```yaml
# k8s/app/flowforge-api/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: flowforge-api
  namespace: flowforge
  labels:
    app: flowforge-api
spec:
  type: ClusterIP
  selector:
    app: flowforge-api
  ports:
    - port: 8080
      targetPort: 8080
      protocol: TCP
```

#### Ingress

```yaml
# k8s/app/flowforge-api/ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: flowforge-api
  namespace: flowforge
  annotations:
    kubernetes.io/ingress.class: nginx
spec:
  ingressClassName: nginx
  rules:
    - host: flowforge.internal.tesco.com
      http:
        paths:
          - path: /api
            pathType: Prefix
            backend:
              service:
                name: flowforge-api
                port:
                  number: 8080
```

#### HorizontalPodAutoscaler

```yaml
# k8s/app/flowforge-api/hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: flowforge-api
  namespace: flowforge
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: flowforge-api
  minReplicas: 2
  maxReplicas: 6
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

#### Kustomization

```yaml
# k8s/app/flowforge-api/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: flowforge
resources:
  - deployment.yaml
  - service.yaml
  - ingress.yaml
  - hpa.yaml
```

### 4.2 REST controllers

#### Snapshot APIs

```java
@RestController
@RequestMapping("/api/v1/snapshots")
public class SnapshotController {

    @PostMapping("/master")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse createBaselineSnapshot(@Valid @RequestBody SnapshotRequest request) {
        // Creates job, returns job_id for polling
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse refreshSnapshot() {
        // Incremental refresh from latest snapshot
    }
}
```

#### Log ingestion APIs

```java
@RestController
@RequestMapping("/api/v1/logs")
public class LogIngestionController {

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse ingestLogs(@Valid @RequestBody LogIngestRequest request) { ... }

    @PostMapping("/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse reindexLogs(@Valid @RequestBody ReindexRequest request) { ... }
}
```

#### Research APIs

```java
@RestController
@RequestMapping("/api/v1/research")
public class ResearchController {

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse startResearchRun(@Valid @RequestBody ResearchRunRequest request) { ... }

    @GetMapping("/latest")
    public ResearchRunResponse getLatestResearch() { ... }

    @GetMapping("/{runId}")
    public ResearchRunResponse getResearchRun(@PathVariable UUID runId) { ... }

    @GetMapping("/{runId}/artifact")
    public ResponseEntity<Resource> downloadArtifact(@PathVariable UUID runId) { ... }
}
```

#### Job & health APIs

```java
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {
    @GetMapping("/{jobId}")
    public JobStatusResponse getJobStatus(@PathVariable UUID jobId) { ... }
}
```

### 4.3 Request/response records

```java
public record SnapshotRequest(
    @NotBlank String repoUrl,
    String githubToken
) {}

public record LogIngestRequest(
    String storageAccount,
    String container,
    String prefix,
    @NotNull IngestionMode mode
) {
    public enum IngestionMode { FULL, INCREMENTAL }
}

public record ResearchRunRequest(
    UUID snapshotId,
    UUID blobBatchId
) {}

public record JobResponse(UUID jobId, String status, String message) {}

public record JobStatusResponse(
    UUID jobId, String jobType, String status,
    double progressPct, Instant createdAt,
    Instant startedAt, Instant completedAt,
    String errorMessage
) {}

public record ResearchRunResponse(
    UUID runId, UUID snapshotId, UUID blobBatchId,
    String status, Instant createdAt, Instant completedAt,
    Map<String, Object> qualityMetrics, String outputPath
) {}

public record HealthResponse(
    String status,
    Map<String, String> components
) {}
```

### 4.4 Job dispatch pattern (strategy)

```java
public interface JobDispatcher {
    void dispatch(String jobType, UUID jobId, Map<String, Object> params);
}

@Component
@ConditionalOnProperty(name = "flowforge.dispatch.mode", havingValue = "stub")
public class StubJobDispatcher implements JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(StubJobDispatcher.class);

    @Override
    public void dispatch(String jobType, UUID jobId, Map<String, Object> params) {
        log.info("STUB: Would dispatch job {} of type {} with params {}", jobId, jobType, params);
    }
}

@Component
@ConditionalOnProperty(name = "flowforge.dispatch.mode", havingValue = "in-process", matchIfMissing = true)
public class InProcessJobDispatcher implements JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InProcessJobDispatcher.class);

    private final ApplicationContext applicationContext;
    private final MetadataService metadataService;

    public InProcessJobDispatcher(ApplicationContext applicationContext, MetadataService metadataService) {
        this.applicationContext = applicationContext;
        this.metadataService = metadataService;
    }

    @Override
    public void dispatch(String jobType, UUID jobId, Map<String, Object> params) {
        Thread.startVirtualThread(() -> {
            try {
                metadataService.updateJobStatus(jobId, Status.RUNNING, 0.0f);
                switch (jobType) {
                    case "SNAPSHOT" -> applicationContext.getBean("snapshotWorker", Runnable.class).run();
                    case "LOG_INGEST" -> applicationContext.getBean("blobIngestionWorker", Runnable.class).run();
                    case "RESEARCH" -> applicationContext.getBean("researchPipeline", Runnable.class).run();
                    default -> throw new IllegalArgumentException("Unknown job type: " + jobType);
                }
                metadataService.updateJobStatus(jobId, Status.COMPLETED, 100.0f);
            } catch (Exception e) {
                log.error("Job {} failed: {}", jobId, e.getMessage(), e);
                metadataService.updateJobStatus(jobId, Status.FAILED, -1.0f);
            }
        });
    }
}
```

> **Dispatcher lifecycle:** During Stages 05-27, the `InProcessJobDispatcher` executes
> jobs on virtual threads within the same JVM. In Stage 28 (Argo), execution moves to
> Argo workflow pods. In Stage 29 (Dapr), a `DaprJobDispatcher` replaces this for
> service-to-service invocation. All dispatchers implement the `JobDispatcher` interface.

### 4.5 Health check (Spring Actuator)

Leverages Spring Boot Actuator with custom health contributors:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  health:
    show-details: always
```

### 4.6 Dockerfile

```dockerfile
FROM flowforge-base:latest
COPY services/api/build/libs/api-*.jar /app/app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s CMD curl -f http://localhost:8080/actuator/health || exit 1
```

### 4.7 Dependencies

```kotlin
dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(project(":libs:common"))
}
```

## Testing & Verification Strategy

### Unit Tests

**Test class:** `services/api/src/test/java/com/flowforge/api/controller/SnapshotControllerTest.java`

Uses `@WebMvcTest` to test REST endpoints in isolation with mocked service layer.

```java
@WebMvcTest(SnapshotController.class)
class SnapshotControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private MetadataService metadataService;
    @MockitoBean private JobDispatcher jobDispatcher;

    @Test
    void createBaselineSnapshotReturns202WithJobId() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(metadataService.createJob(eq("SNAPSHOT"), any()))
            .thenReturn(jobId);

        mockMvc.perform(post("/api/v1/snapshots/master")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"repoUrl": "https://github.com/org/repo", "githubToken": "ghp_xxx"}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value(jobId.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"));

        verify(jobDispatcher).dispatch(eq("SNAPSHOT"), eq(jobId), any());
    }

    @Test
    void createBaselineSnapshotRejects400WhenRepoUrlBlank() throws Exception {
        mockMvc.perform(post("/api/v1/snapshots/master")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"repoUrl": "", "githubToken": "ghp_xxx"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void refreshSnapshotReturns202() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(metadataService.createJob(eq("SNAPSHOT_REFRESH"), any()))
            .thenReturn(jobId);

        mockMvc.perform(post("/api/v1/snapshots/refresh"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }
}
```

**Test class:** `services/api/src/test/java/com/flowforge/api/controller/LogIngestionControllerTest.java`

```java
@WebMvcTest(LogIngestionController.class)
class LogIngestionControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MetadataService metadataService;
    @MockitoBean private JobDispatcher jobDispatcher;

    @Test
    void ingestLogsReturns202() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(metadataService.createJob(eq("LOG_INGEST"), any())).thenReturn(jobId);

        mockMvc.perform(post("/api/v1/logs/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "storageAccount": "myaccount",
                      "container": "logs",
                      "prefix": "2024/",
                      "mode": "FULL"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    void ingestLogsRejects400WhenModeNull() throws Exception {
        mockMvc.perform(post("/api/v1/logs/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"storageAccount": "a", "container": "c", "prefix": "p"}
                    """))
            .andExpect(status().isBadRequest());
    }
}
```

**Test class:** `services/api/src/test/java/com/flowforge/api/controller/JobControllerTest.java`

```java
@WebMvcTest(JobController.class)
class JobControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MetadataService metadataService;

    @Test
    void getJobStatusReturns200() throws Exception {
        UUID jobId = UUID.randomUUID();
        var job = new JobEntity();
        job.setJobId(jobId);
        job.setJobType("SNAPSHOT");
        job.setStatus(Status.RUNNING);
        job.setProgressPct(50.0f);
        job.setCreatedAt(Instant.now());
        when(metadataService.getJob(jobId)).thenReturn(Optional.of(job));

        mockMvc.perform(get("/api/v1/jobs/{id}", jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.progressPct").value(50.0));
    }

    @Test
    void getJobStatusReturns404WhenNotFound() throws Exception {
        when(metadataService.getJob(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/jobs/{id}", UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }
}
```

**Test class:** `services/api/src/test/java/com/flowforge/api/service/InProcessJobDispatcherTest.java`

Tests the dispatcher strategy pattern and virtual thread execution.

```java
@ExtendWith(MockitoExtension.class)
class InProcessJobDispatcherTest {

    @Mock private MetadataService metadataService;
    @Mock private ApplicationContext applicationContext;

    @Test
    void dispatchExecutesSnapshotWorkerOnVirtualThread() throws Exception {
        var worker = mock(Runnable.class);
        when(applicationContext.getBean("snapshotWorker", Runnable.class)).thenReturn(worker);
        var dispatcher = new InProcessJobDispatcher(applicationContext, metadataService);
        UUID jobId = UUID.randomUUID();

        dispatcher.dispatch("SNAPSHOT", jobId, Map.of("repoUrl", "https://github.com/org/repo"));

        Thread.sleep(200);
        verify(metadataService).updateJobStatus(jobId, Status.RUNNING, 0.0f);
        verify(worker).run();
        verify(metadataService).updateJobStatus(jobId, Status.COMPLETED, 100.0f);
    }

    @Test
    void dispatchMarksJobFailedForUnknownType() throws Exception {
        var dispatcher = new InProcessJobDispatcher(applicationContext, metadataService);
        UUID jobId = UUID.randomUUID();

        dispatcher.dispatch("UNKNOWN_TYPE", jobId, Map.of());

        Thread.sleep(200);
        verify(metadataService).updateJobStatus(jobId, Status.FAILED, -1.0f);
    }

    @Test
    void dispatchMarksJobFailedOnWorkerException() throws Exception {
        var worker = mock(Runnable.class);
        doThrow(new RuntimeException("boom")).when(worker).run();
        when(applicationContext.getBean("snapshotWorker", Runnable.class)).thenReturn(worker);
        var dispatcher = new InProcessJobDispatcher(applicationContext, metadataService);
        UUID jobId = UUID.randomUUID();

        dispatcher.dispatch("SNAPSHOT", jobId, Map.of());

        Thread.sleep(200);
        verify(metadataService).updateJobStatus(jobId, Status.FAILED, -1.0f);
    }
}
```

### Integration Tests

**Test class:** `services/api/src/test/java/com/flowforge/api/FlowForgeApiIntegrationTest.java`

Full application context test with Testcontainers PostgreSQL, verifying end-to-end REST flows.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@Tag("integration")
class FlowForgeApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("flowforge_test")
        .withUsername("flowforge")
        .withPassword("flowforge");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private TestRestTemplate restTemplate;

    @Test
    void healthEndpointReturnsUp() {
        var response = restTemplate.getForEntity("/actuator/health", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void snapshotEndToEndFlow() {
        var request = new SnapshotRequest("https://github.com/org/repo", "ghp_token");
        var response = restTemplate.postForEntity(
            "/api/v1/snapshots/master", request, JobResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().jobId()).isNotNull();

        // Poll job status
        var statusResponse = restTemplate.getForEntity(
            "/api/v1/jobs/{id}", JobStatusResponse.class, response.getBody().jobId());
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResponse.getBody().jobType()).isEqualTo("SNAPSHOT");
    }

    @Test
    void inputValidationReturns400() {
        var request = new SnapshotRequest("", null);
        var response = restTemplate.postForEntity(
            "/api/v1/snapshots/master", request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

### Test Fixtures & Sample Data

| File | Description |
|---|---|
| `services/api/src/test/resources/application-test.yml` | Spring profile config pointing at Testcontainers-managed PostgreSQL |
| `src/test/resources/fixtures/snapshot-request-valid.json` | Valid `SnapshotRequest` payload |
| `src/test/resources/fixtures/snapshot-request-invalid.json` | Invalid payload (blank `repoUrl`) for 400 tests |
| `src/test/resources/fixtures/log-ingest-request-full.json` | Valid `LogIngestRequest` with `FULL` mode |
| `src/test/resources/fixtures/log-ingest-request-incremental.json` | Valid `LogIngestRequest` with `INCREMENTAL` mode |
| `src/test/resources/fixtures/research-run-request.json` | Valid `ResearchRunRequest` with snapshot and batch IDs |

### Mocking Strategy

| Component | Strategy |
|---|---|
| `MetadataService` | **Mocked** via `@MockitoBean` in `@WebMvcTest` controller tests |
| `JobDispatcher` | **Mocked** via `@MockitoBean` in controller tests; verify `dispatch()` is called with correct args |
| `ApplicationContext` | **Mocked** with Mockito in `InProcessJobDispatcherTest` |
| PostgreSQL | **Real** via Testcontainers in integration tests |
| `MinioStorageClient` | **Mocked** via `@MockitoBean` in API integration tests (MinIO not under test here) |
| `StubJobDispatcher` | Activated via `@ActiveProfiles("stub")` to bypass real dispatch in controller-only tests |

### CI/CD Considerations

- **Test tags:** `@Tag("unit")` for `@WebMvcTest` and mock-based tests, `@Tag("integration")` for `@SpringBootTest` with Testcontainers.
- **Docker requirements:** CI runners need Docker for Testcontainers PostgreSQL. The `postgres:16-alpine` image is ~80MB.
- **Profile-based dispatch:** Use `spring.profiles.active=stub,test` in CI to isolate API tests from downstream stages. The `StubJobDispatcher` logs dispatch calls without executing pipeline stages.
- **OpenAPI validation:** Add `springdoc-openapi` dependency and generate an OpenAPI spec in CI. Validate with `swagger-cli validate`:
  ```bash
  curl -s http://localhost:8080/v3/api-docs | swagger-cli validate -
  ```
- **CI pipeline stages:** `compile` → `unit-test` → `integration-test` (Docker) → `api-spec-validate` → `docker-build`.
- **Test parallelism:** Controller tests (`@WebMvcTest`) are lightweight and safe to run in parallel. Integration tests share a Testcontainers PostgreSQL instance.

## Verification

**Stage 4 sign-off requires all stages 1 through 4 to pass.** Run: `make verify`.

The verification report for stage 4 is `logs/stage-04.log`. It contains **cumulative output for stages 1–4** (Stage 1, then Stage 2, then Stage 3, then Stage 4 output).

| Check | How to verify | Pass criteria |
|---|---|---|
| App starts | `./gradlew :services:api:bootRun` | Server starts on 8080 |
| Health endpoint | `GET /actuator/health` | JSON with component statuses |
| Create snapshot job | `POST /api/v1/snapshots/master` | Returns 202 + job_id |
| Get job status | `GET /api/v1/jobs/{id}` | Returns job with PENDING |
| Create log ingest | `POST /api/v1/logs/ingest` | Returns 202 |
| Create research run | `POST /api/v1/research/run` | Returns 202 |
| Input validation | Missing `repoUrl` | Returns 400 with errors |
| OpenAPI docs | `GET /swagger-ui.html` (springdoc) | Swagger UI renders |
| Docker build | `./gradlew :services:api:bootBuildImage` | Image builds |
| API tests | `./gradlew :services:api:test` | All pass |
| ArgoCD sync | ArgoCD UI → `flowforge-api` app | Synced, Healthy |
| K8s deployment | `kubectl get deploy -n flowforge flowforge-api` | 2/2 replicas ready |
| K8s service | `kubectl get svc -n flowforge flowforge-api` | ClusterIP on port 8080 |
| Ingress | `curl -H 'Host: flowforge.internal.tesco.com' http://<ingress-ip>/api/actuator/health` | 200 OK |
| HPA | `kubectl get hpa -n flowforge flowforge-api` | Min 2, Max 6, target 70% CPU |
| Dapr sidecar | `kubectl get pods -n flowforge -l app=flowforge-api -o jsonpath='{.items[0].spec.containers[*].name}'` | Contains `daprd` sidecar |

## Files to create

- `services/api/build.gradle.kts`
- `services/api/src/main/java/com/flowforge/api/FlowForgeApiApplication.java`
- `services/api/src/main/java/com/flowforge/api/controller/SnapshotController.java`
- `services/api/src/main/java/com/flowforge/api/controller/LogIngestionController.java`
- `services/api/src/main/java/com/flowforge/api/controller/ResearchController.java`
- `services/api/src/main/java/com/flowforge/api/controller/JobController.java`
- `services/api/src/main/java/com/flowforge/api/dto/*.java` (request/response records)
- `services/api/src/main/java/com/flowforge/api/service/JobDispatcher.java`
- `services/api/src/main/java/com/flowforge/api/service/StubJobDispatcher.java`
- `services/api/src/main/resources/application.yml`
- `services/api/Dockerfile`
- `services/api/src/test/java/com/flowforge/api/controller/SnapshotControllerTest.java`
- `services/api/src/test/java/com/flowforge/api/controller/ResearchControllerTest.java`
- `services/api/src/test/java/com/flowforge/api/FlowForgeApiIntegrationTest.java`
- `k8s/argocd/apps/flowforge-api.yaml`
- `k8s/app/flowforge-api/deployment.yaml`
- `k8s/app/flowforge-api/service.yaml`
- `k8s/app/flowforge-api/ingress.yaml`
- `k8s/app/flowforge-api/hpa.yaml`
- `k8s/app/flowforge-api/kustomization.yaml`

## Depends on

- Stage 01 (project structure, shared libs)
- Stage 02 (PostgreSQL metadata)
- Stage 03 (MinIO — for health check)

## Produces

- Running Spring Boot REST API with all endpoints
- Job creation and status tracking via JPA
- Stub dispatcher for future replacement with Dapr
- Spring Actuator health + metrics
- Docker image for API service
