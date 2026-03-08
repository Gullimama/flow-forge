# Stage 01 — Project Scaffolding & Repository Structure

## Goal

Create the Gradle multi-module monorepo structure, base Spring Boot configuration, shared libraries, Docker/Helm foundations, and build tooling so that every subsequent stage has a consistent project to build into.

## Prerequisites

- Git repository initialized
- Java 25 (JDK 25) installed
- Gradle 8.x available
- Docker / Podman available
- Helm CLI available
- Access to a container registry (ACR or local)

## What to build

### 1.1 Monorepo directory layout

```
flowforge/
├── settings.gradle.kts              # Multi-module project settings
├── build.gradle.kts                 # Root build config
├── gradle/
│   └── libs.versions.toml           # Version catalog
├── services/
│   ├── api/                          # FlowForge control plane API (Stage 04)
│   └── orchestrator/                # Argo workflow management (Stage 28)
├── libs/
│   ├── common/                      # Shared models, utils, config
│   │   └── src/main/java/com/flowforge/common/
│   │       ├── config/              # Spring @ConfigurationProperties
│   │       ├── model/               # Shared Java records
│   │       ├── client/              # Store client wrappers
│   │       └── util/                # Utilities
│   └── test-fixtures/               # Shared test data & fixtures
├── k8s/
│   ├── argocd/
│   │   ├── project.yaml             # ArgoCD AppProject definition
│   │   ├── app-of-apps.yaml         # Root Application (manages all children)
│   │   └── apps/                    # One ArgoCD Application per component
│   │       ├── postgresql.yaml      #   Helm: bitnami/postgresql
│   │       ├── minio.yaml           #   Helm: bitnami/minio
│   │       ├── opensearch.yaml      #   Helm: opensearch/opensearch
│   │       ├── neo4j.yaml           #   Helm: neo4j/neo4j
│   │       ├── qdrant.yaml          #   Helm: qdrant/qdrant
│   │       ├── redis.yaml           #   Helm: bitnami/redis
│   │       ├── tei-code.yaml        #   Kustomize: k8s/ml-serving/tei-code/
│   │       ├── tei-log.yaml         #   Kustomize: k8s/ml-serving/tei-log/
│   │       ├── tei-reranker.yaml    #   Kustomize: k8s/ml-serving/tei-reranker/
│   │       ├── vllm.yaml            #   Kustomize: k8s/ml-serving/vllm/
│   │       ├── argo-workflows.yaml  #   Helm: argo/argo-workflows
│   │       ├── dapr.yaml            #   Helm: dapr/dapr
│   │       ├── kube-prometheus-stack.yaml
│   │       ├── tempo.yaml           #   Helm: grafana/tempo
│   │       ├── mlflow.yaml          #   Kustomize: k8s/observability/mlflow/
│   │       ├── flowforge-api.yaml   #   Kustomize: k8s/app/flowforge-api/
│   │       └── flowforge-dapr.yaml  #   Kustomize: k8s/dapr/
│   ├── infrastructure/              # Helm values for data stores
│   │   ├── postgresql/
│   │   │   └── values.yaml
│   │   ├── minio/
│   │   │   └── values.yaml
│   │   ├── opensearch/
│   │   │   └── values.yaml
│   │   ├── neo4j/
│   │   │   └── values.yaml
│   │   ├── qdrant/
│   │   │   └── values.yaml
│   │   └── redis/
│   │       └── values.yaml
│   ├── ml-serving/                  # Kustomize manifests for GPU workloads
│   │   ├── tei-code/
│   │   │   ├── kustomization.yaml
│   │   │   ├── deployment.yaml
│   │   │   ├── service.yaml
│   │   │   └── pvc.yaml
│   │   ├── tei-log/
│   │   │   ├── kustomization.yaml
│   │   │   ├── deployment.yaml
│   │   │   ├── service.yaml
│   │   │   └── pvc.yaml
│   │   ├── tei-reranker/
│   │   │   ├── kustomization.yaml
│   │   │   ├── deployment.yaml
│   │   │   ├── service.yaml
│   │   │   └── pvc.yaml
│   │   └── vllm/
│   │       ├── kustomization.yaml
│   │       ├── deployment.yaml
│   │       ├── service.yaml
│   │       └── pvc.yaml
│   ├── app/                         # Application manifests
│   │   └── flowforge-api/
│   │       ├── kustomization.yaml
│   │       ├── deployment.yaml
│   │       ├── service.yaml
│   │       ├── ingress.yaml
│   │       ├── configmap.yaml
│   │       ├── serviceaccount.yaml
│   │       └── hpa.yaml
│   ├── observability/               # Monitoring stack Helm values
│   │   ├── kube-prometheus-stack/
│   │   │   └── values.yaml
│   │   ├── tempo/
│   │   │   └── values.yaml
│   │   └── mlflow/
│   │       ├── kustomization.yaml
│   │       ├── deployment.yaml
│   │       ├── service.yaml
│   │       └── pvc.yaml
│   ├── argo/                        # Argo Workflow templates
│   │   ├── flowforge-pipeline.yaml
│   │   └── workflow-rbac.yaml
│   ├── dapr/                        # Dapr component definitions
│   │   ├── pubsub.yaml
│   │   ├── statestore.yaml
│   │   └── secretstore.yaml
│   ├── grafana/                     # Grafana dashboard JSON exports
│   │   └── flowforge-dashboard.json
│   └── monitoring/                  # PrometheusRule alert definitions
│       └── flowforge-alerts.yaml
├── docker/
│   ├── Dockerfile.base              # JDK 25 base image
│   ├── Dockerfile.gpu-base          # GPU base image (CUDA + DJL)
│   └── docker-compose.yml           # LOCAL DEV ONLY — not used on AKS
├── src/test/resources/
│   └── fixtures/                    # Shared test fixtures
├── Makefile                         # Common commands
├── .env.example                     # Environment variable template
└── README.md
```

### 1.2 Gradle version catalog

`gradle/libs.versions.toml`:
```toml
[versions]
java = "25"
spring-boot = "4.0.3"
spring-ai = "1.0.3"
spring-data-neo4j = "7.4.0"
minio = "8.5.13"
opensearch = "2.18.0"
qdrant = "1.12.1"
javaparser = "3.26.3"
djl = "0.30.0"
smile = "3.1.1"
spmf = "2.60"
flyway = "11.1.0"
testcontainers = "1.20.6"
freemarker = "2.3.33"
resilience4j = "2.2.0"
micrometer = "1.15.0"
jackson = "2.18.3"
fabric8 = "7.0.1"

[libraries]
spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web" }
spring-boot-starter-data-jpa = { module = "org.springframework.boot:spring-boot-starter-data-jpa" }
spring-boot-starter-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }
spring-ai-openai-spring-boot-starter = { module = "org.springframework.ai:spring-ai-openai-spring-boot-starter", version.ref = "spring-ai" }
spring-ai-qdrant-store = { module = "org.springframework.ai:spring-ai-qdrant-store-spring-boot-starter", version.ref = "spring-ai" }
spring-data-neo4j = { module = "org.springframework.boot:spring-boot-starter-data-neo4j" }
minio = { module = "io.minio:minio", version.ref = "minio" }
opensearch-client = { module = "org.opensearch.client:opensearch-java", version.ref = "opensearch" }
javaparser = { module = "com.github.javaparser:javaparser-core", version.ref = "javaparser" }
djl-api = { module = "ai.djl:api", version.ref = "djl" }
djl-pytorch = { module = "ai.djl.pytorch:pytorch-engine", version.ref = "djl" }
djl-onnxruntime = { module = "ai.djl.onnxruntime:onnxruntime-engine", version.ref = "djl" }
smile-core = { module = "com.github.haifengl:smile-core", version.ref = "smile" }
freemarker = { module = "org.freemarker:freemarker", version.ref = "freemarker" }
resilience4j-spring-boot3 = { module = "io.github.resilience4j:resilience4j-spring-boot3", version.ref = "resilience4j" }
fabric8-kubernetes-client = { module = "io.fabric8:kubernetes-client", version.ref = "fabric8" }
flyway-core = { module = "org.flywaydb:flyway-core", version.ref = "flyway" }
flyway-postgresql = { module = "org.flywaydb:flyway-database-postgresql", version.ref = "flyway" }
postgresql = { module = "org.postgresql:postgresql" }
testcontainers-junit5 = { module = "org.testcontainers:junit-jupiter", version.ref = "testcontainers" }
testcontainers-postgresql = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }
testcontainers-neo4j = { module = "org.testcontainers:neo4j", version.ref = "testcontainers" }
testcontainers-minio = { module = "org.testcontainers:minio", version.ref = "testcontainers" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-dependency-management = { id = "io.spring.dependency-management", version = "1.1.8" }
```

### 1.3 Shared configuration framework

`libs/common/src/main/java/com/flowforge/common/config/FlowForgeProperties.java`:

```java
@ConfigurationProperties(prefix = "flowforge")
public record FlowForgeProperties(
    MinioProperties minio,
    OpenSearchProperties opensearch,
    QdrantProperties qdrant,
    Neo4jProperties neo4j,
    PostgresProperties postgres,
    VllmProperties vllm,
    TeiProperties tei
) {
    public record MinioProperties(String endpoint, String accessKey, String secretKey, boolean secure) {}
    public record OpenSearchProperties(List<String> hosts, String username, String password, String indexPrefix) {}
    public record QdrantProperties(String host, int port, String apiKey, String collectionPrefix) {}
    public record Neo4jProperties(String uri, String user, String password, String database) {}
    public record PostgresProperties(String url, String username, String password) {}
    public record VllmProperties(String baseUrl, String apiKey, String model) {}
    public record TeiProperties(String codeUrl, String logUrl, String rerankerUrl) {}
}
```

### 1.4 Shared data models (Java records)

`libs/common/src/main/java/com/flowforge/common/model/`:

```java
public record SnapshotMetadata(
    UUID snapshotId,
    String repoUrl,
    String branch,
    String commitSha,
    SnapshotType snapshotType,
    Instant createdAt,
    List<String> changedFiles
) {
    public enum SnapshotType { BASELINE, REFRESH }
}

public record BlobIngestionRecord(
    UUID batchId,
    String storageAccount,
    String container,
    String prefix,
    String blobName,
    String etag,
    long contentLength,
    Instant lastModified
) {}

public record JobStatus(
    UUID jobId,
    String jobType,
    Status status,
    Instant createdAt,
    Instant updatedAt,
    String errorMessage,
    double progressPct
) {
    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }
}

public record RuntimeEvent(
    String eventId,
    Instant timestamp,
    SourceType sourceType,
    String service,
    String namespace,
    String pod,
    String traceId,
    String spanId,
    String correlationId,
    String requestId,
    String httpMethod,
    String path,
    Integer statusCode,
    Double latencyMs,
    String targetService,
    String exceptionType,
    String message,
    Map<String, String> tags
) {
    public enum SourceType { APP, ISTIO }
}
```

### 1.5 Store client wrappers

Each wrapper in `libs/common/src/main/java/com/flowforge/common/client/` should:
- Accept its config via Spring `@ConfigurationProperties`
- Provide a typed interface (Spring `@Component`)
- Include health check methods (contribute to Spring Actuator)
- Be independently testable with Testcontainers

### 1.6 Docker base images

`docker/Dockerfile.base`:
```dockerfile
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S flowforge && adduser -S flowforge -G flowforge
USER flowforge
ENTRYPOINT ["java", "-jar"]
```

`docker/Dockerfile.gpu-base`:
```dockerfile
FROM nvidia/cuda:12.6.0-runtime-ubuntu24.04
RUN apt-get update && apt-get install -y openjdk-25-jre-headless && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ENTRYPOINT ["java", "-jar"]
```

### 1.7 Makefile targets

```makefile
.PHONY: build test lint docker

build:              ## Build all modules
	./gradlew build -x test

test:               ## Run all tests
	./gradlew test

test-unit:          ## Run unit tests only
	./gradlew test --tests '*Unit*'

test-integration:   ## Run integration tests (requires Docker)
	./gradlew test --tests '*Integration*'

lint:               ## Run checkstyle + spotbugs
	./gradlew checkstyleMain spotbugsMain

docker:             ## Build all Docker images
	./gradlew bootBuildImage

k8s-validate:       ## Validate all K8s manifests
	kubectl kustomize k8s/ml-serving/tei-code/ --enable-helm | kubectl apply --dry-run=client -f -
	kubectl kustomize k8s/ml-serving/vllm/ --enable-helm | kubectl apply --dry-run=client -f -
	kubectl kustomize k8s/app/flowforge-api/ --enable-helm | kubectl apply --dry-run=client -f -

argocd-diff:        ## Show ArgoCD diff for all apps
	argocd app diff flowforge-root --local k8s/argocd/
```

### 1.8 ArgoCD bootstrap

FlowForge uses ArgoCD with an App-of-Apps pattern. All infrastructure and application deployments are declared in `k8s/argocd/` and synced from Git. See the architecture document §12 for the full strategy.

**ArgoCD Project:**
```yaml
# k8s/argocd/project.yaml
apiVersion: argoproj.io/v1alpha1
kind: AppProject
metadata:
  name: flowforge
  namespace: argocd
spec:
  description: FlowForge research platform
  sourceRepos:
    - 'https://github.com/tesco/flow-forge.git'
    - 'https://charts.bitnami.com/bitnami'
    - 'https://opensearch-project.github.io/helm-charts'
    - 'https://helm.neo4j.com/neo4j'
    - 'https://qdrant.github.io/qdrant-helm'
    - 'https://argoproj.github.io/argo-helm'
    - 'https://dapr.github.io/helm-charts'
    - 'https://prometheus-community.github.io/helm-charts'
    - 'https://grafana.github.io/helm-charts'
  destinations:
    - namespace: 'flowforge*'
      server: https://kubernetes.default.svc
    - namespace: 'argo'
      server: https://kubernetes.default.svc
    - namespace: 'dapr-system'
      server: https://kubernetes.default.svc
    - namespace: 'flowforge-obs'
      server: https://kubernetes.default.svc
  clusterResourceWhitelist:
    - group: ''
      kind: Namespace
    - group: rbac.authorization.k8s.io
      kind: ClusterRole
    - group: rbac.authorization.k8s.io
      kind: ClusterRoleBinding
```

**Root App-of-Apps:**
```yaml
# k8s/argocd/app-of-apps.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: flowforge-root
  namespace: argocd
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: flowforge
  source:
    repoURL: https://github.com/tesco/flow-forge.git
    targetRevision: HEAD
    path: k8s/argocd/apps
  destination:
    server: https://kubernetes.default.svc
    namespace: argocd
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - PrunePropagationPolicy=foreground
```

> **Bootstrap:** Apply the project and root app once manually (`kubectl apply -f k8s/argocd/project.yaml && kubectl apply -f k8s/argocd/app-of-apps.yaml`). All subsequent infrastructure and application deployments are managed by ArgoCD from Git.

### 1.9 AKS namespace layout

```yaml
# k8s/argocd/apps/namespaces.yaml (sync-wave 0)
apiVersion: v1
kind: Namespace
metadata:
  name: flowforge
  annotations:
    argocd.argoproj.io/sync-wave: "0"
---
apiVersion: v1
kind: Namespace
metadata:
  name: flowforge-infra
  annotations:
    argocd.argoproj.io/sync-wave: "0"
---
apiVersion: v1
kind: Namespace
metadata:
  name: flowforge-ml
  annotations:
    argocd.argoproj.io/sync-wave: "0"
---
apiVersion: v1
kind: Namespace
metadata:
  name: flowforge-obs
  annotations:
    argocd.argoproj.io/sync-wave: "0"
```

## Testing & Verification Strategy

### Unit Tests

**Test class:** `libs/common/src/test/java/com/flowforge/common/config/FlowForgePropertiesTest.java`

Validates that `FlowForgeProperties` binds correctly from YAML configuration and that all nested records resolve.

```java
@SpringBootTest(classes = FlowForgePropertiesTest.TestConfig.class)
@ActiveProfiles("test")
class FlowForgePropertiesTest {

    @EnableConfigurationProperties(FlowForgeProperties.class)
    @Configuration
    static class TestConfig {}

    @Autowired
    private FlowForgeProperties props;

    @Test
    void minioPropertiesBoundFromYaml() {
        assertThat(props.minio()).isNotNull();
        assertThat(props.minio().endpoint()).isEqualTo("http://localhost:9000");
        assertThat(props.minio().accessKey()).isNotBlank();
    }

    @Test
    void opensearchPropertiesBoundFromYaml() {
        assertThat(props.opensearch()).isNotNull();
        assertThat(props.opensearch().hosts()).isNotEmpty();
    }

    @Test
    void allNestedRecordSectionsPopulated() {
        assertAll(
            () -> assertThat(props.minio()).isNotNull(),
            () -> assertThat(props.opensearch()).isNotNull(),
            () -> assertThat(props.qdrant()).isNotNull(),
            () -> assertThat(props.neo4j()).isNotNull(),
            () -> assertThat(props.postgres()).isNotNull(),
            () -> assertThat(props.vllm()).isNotNull(),
            () -> assertThat(props.tei()).isNotNull()
        );
    }
}
```

**Test class:** `libs/common/src/test/java/com/flowforge/common/model/SharedModelsTest.java`

Validates Java record serialization/deserialization round-trips via Jackson.

```java
class SharedModelsTest {

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void snapshotMetadataSerializationRoundTrip() throws Exception {
        var meta = new SnapshotMetadata(
            UUID.randomUUID(), "https://github.com/org/repo", "master",
            "abc123", SnapshotMetadata.SnapshotType.BASELINE,
            Instant.now(), List.of("src/Main.java")
        );
        String json = mapper.writeValueAsString(meta);
        var deserialized = mapper.readValue(json, SnapshotMetadata.class);
        assertThat(deserialized).isEqualTo(meta);
    }

    @Test
    void blobIngestionRecordSerializationRoundTrip() throws Exception {
        var record = new BlobIngestionRecord(
            UUID.randomUUID(), "account", "container", "prefix/",
            "app.log.gz", "etag-1", 1024L, Instant.now()
        );
        String json = mapper.writeValueAsString(record);
        var deserialized = mapper.readValue(json, BlobIngestionRecord.class);
        assertThat(deserialized).isEqualTo(record);
    }

    @Test
    void jobStatusSerializationRoundTrip() throws Exception {
        var status = new JobStatus(
            UUID.randomUUID(), "SNAPSHOT", JobStatus.Status.RUNNING,
            Instant.now(), Instant.now(), null, 45.5
        );
        String json = mapper.writeValueAsString(status);
        var deserialized = mapper.readValue(json, JobStatus.class);
        assertThat(deserialized).isEqualTo(status);
    }

    @Test
    void runtimeEventSerializationRoundTrip() throws Exception {
        var event = new RuntimeEvent(
            "evt-1", Instant.now(), RuntimeEvent.SourceType.APP,
            "booking-service", "prod", "pod-1", "trace-1", "span-1",
            "corr-1", "req-1", "GET", "/api/bookings", 200, 12.5,
            "payment-service", null, "OK", Map.of("env", "prod")
        );
        String json = mapper.writeValueAsString(event);
        var deserialized = mapper.readValue(json, RuntimeEvent.class);
        assertThat(deserialized).isEqualTo(event);
    }

    @Test
    void jobStatusEnumValuesAreComplete() {
        assertThat(JobStatus.Status.values()).containsExactly(
            JobStatus.Status.PENDING, JobStatus.Status.RUNNING,
            JobStatus.Status.COMPLETED, JobStatus.Status.FAILED,
            JobStatus.Status.CANCELLED
        );
    }
}
```

### Integration Tests

No Testcontainers required for Stage 01 since it focuses on project scaffolding. Integration-level validation is done through Gradle build verification.

**Test class:** `libs/common/src/test/java/com/flowforge/common/config/FlowForgePropertiesIntegrationTest.java`

```java
@SpringBootTest
@ActiveProfiles("test")
class FlowForgePropertiesIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void flowForgePropertiesBeanExistsInContext() {
        assertThat(context.getBean(FlowForgeProperties.class)).isNotNull();
    }

    @Test
    void configurationBindingFailsFastOnMissingRequiredFields() {
        var app = new SpringApplicationBuilder(FlowForgePropertiesIntegrationTest.class)
            .profiles("missing-config")
            .build();
        assertThrows(BeanCreationException.class, () -> app.run());
    }
}
```

### Test Fixtures & Sample Data

Create `libs/common/src/test/resources/application-test.yml` with all `FlowForgeProperties` sections populated:

```yaml
flowforge:
  minio:
    endpoint: http://localhost:9000
    access-key: minioadmin
    secret-key: minioadmin
    secure: false
  opensearch:
    hosts:
      - http://localhost:9200
    username: admin
    password: admin
    index-prefix: flowforge-test
  qdrant:
    host: localhost
    port: 6334
    api-key: ""
    collection-prefix: flowforge-test
  neo4j:
    uri: bolt://localhost:7687
    user: neo4j
    password: password
    database: flowforge
  postgres:
    url: jdbc:postgresql://localhost:5432/flowforge_test
    username: flowforge
    password: flowforge
  vllm:
    base-url: http://localhost:8000
    api-key: ""
    model: Qwen/Qwen3-14B
  tei:
    code-url: http://localhost:8081
    log-url: http://localhost:8082
    reranker-url: http://localhost:8083
```

Create fixture JSON files under `libs/common/src/test/resources/fixtures/`:

| File | Description |
|---|---|
| `snapshot-metadata-baseline.json` | Sample `SnapshotMetadata` for a baseline snapshot |
| `snapshot-metadata-refresh.json` | Sample `SnapshotMetadata` with `changedFiles` populated |
| `blob-ingestion-record.json` | Sample `BlobIngestionRecord` entry |
| `runtime-event-app.json` | Sample `RuntimeEvent` with `SourceType.APP` |
| `runtime-event-istio.json` | Sample `RuntimeEvent` with `SourceType.ISTIO` |

### Mocking Strategy

| Component | Strategy |
|---|---|
| `FlowForgeProperties` | Use real binding from `application-test.yml` — no mocking needed |
| `ObjectMapper` | Use real Jackson instance with `JavaTimeModule` registered |
| External stores (MinIO, PostgreSQL, etc.) | Not applicable in Stage 01; mocking deferred to their respective stages |

### CI/CD Considerations

- **Test tags:** Annotate unit tests with `@Tag("unit")`, integration tests with `@Tag("integration")`.
- **Gradle filtering:**
  ```kotlin
  tasks.test {
      useJUnitPlatform {
          includeTags("unit")
      }
  }

  tasks.register<Test>("integrationTest") {
      useJUnitPlatform {
          includeTags("integration")
      }
  }
  ```
- **Docker requirements:** None for Stage 01 unit tests. Docker is required only for building base images (`Dockerfile.base`, `Dockerfile.gpu-base`) — verify with `docker build` in CI.
- **CI pipeline stages:** `compile` → `unit-test` → `docker-build` → `lint`. No external services needed.
- **Parallel execution:** Unit tests are stateless and safe to run with `maxParallelForks = Runtime.getRuntime().availableProcessors()`.

## Verification

**Stage 1 sign-off requires all stages 1 through 1 to pass.** Run: `make verify`.

The verification report for stage 1 is `logs/stage-01.log`. It contains **cumulative output for stages 1–1** (Stage 1 output only).

| Check | How to verify | Pass criteria |
|---|---|---|
| Directory structure exists | `find . -type d` | All listed directories created |
| Gradle builds | `./gradlew build -x test` | Clean compilation with Java 25 |
| Version catalog resolves | `./gradlew dependencies` | All deps resolved |
| Config loads | Unit test: `FlowForgeProperties` bound from `application-test.yml` | All sections populated |
| Models compile | `./gradlew :libs:common:compileJava` | Java records compile |
| Docker base builds | `docker build -f docker/Dockerfile.base .` | Image builds |
| Makefile works | `make build && make lint` | Both pass |
| Unit tests pass | `make test-unit` | 0 failures |

## Files to create

- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle/libs.versions.toml`
- `libs/common/build.gradle.kts`
- `libs/common/src/main/java/com/flowforge/common/config/FlowForgeProperties.java`
- `libs/common/src/main/java/com/flowforge/common/model/SnapshotMetadata.java`
- `libs/common/src/main/java/com/flowforge/common/model/BlobIngestionRecord.java`
- `libs/common/src/main/java/com/flowforge/common/model/JobStatus.java`
- `libs/common/src/main/java/com/flowforge/common/model/RuntimeEvent.java`
- `docker/Dockerfile.base`
- `docker/Dockerfile.gpu-base`
- `docker/docker-compose.yml` (local dev only)
- `k8s/argocd/project.yaml`
- `k8s/argocd/app-of-apps.yaml`
- `Makefile`
- `.env.example`

## Depends on

- Nothing (this is the foundation)

## Produces

- Gradle multi-module project compiling with Java 25
- Shared config framework via `@ConfigurationProperties`
- Shared data models as Java records
- Base Docker images for JVM and GPU services
- ArgoCD App-of-Apps bootstrap manifests
- AKS namespace definitions
- Build and test tooling
