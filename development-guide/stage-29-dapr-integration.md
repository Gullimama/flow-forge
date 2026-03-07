# Stage 29 — Dapr Integration (Java SDK)

## Goal

Integrate **Dapr** (Distributed Application Runtime) as the service mesh sidecar for inter-service communication, pub/sub messaging, state management, and secret management. Use the Dapr Java SDK with Spring Boot auto-configuration to decouple FlowForge services from specific infrastructure implementations.

## Prerequisites

- Stage 04 (Spring Boot REST API)
- Dapr installed on AKS cluster with components configured

## What to build

### Orchestration Responsibility Split: Argo vs Dapr

> **Argo Workflows** (Stage 28) is the **sole orchestrator** for pipeline DAG execution. It
> controls task ordering, retries, timeouts, and GPU scheduling. Argo determines *when*
> stages run and *whether* they succeeded or failed.
>
> **Dapr** serves three distinct, non-overlapping roles:
> 1. **Service invocation** — mTLS-encrypted RPC between services (replaces direct HTTP calls)
> 2. **State management** — Distributed state for caching intermediate results across pods
> 3. **Secret management** — Centralized secret access via Azure Key Vault
>
> **Dapr pub/sub is used only for observability notifications** (e.g., broadcasting stage
> completion events to monitoring dashboards and alerting systems). It does NOT control
> pipeline flow — Argo DAG dependencies are the source of truth for stage ordering.
> If Dapr pub/sub is unavailable, the pipeline continues normally; only observability
> notifications are lost.

### 29.1 Dapr configuration

```java
@ConfigurationProperties(prefix = "flowforge.dapr")
public record DaprProperties(
    String sidecarHost,
    int sidecarHttpPort,
    int sidecarGrpcPort,
    String pubsubName,
    String stateStoreName,
    String secretStoreName,
    Duration timeout
) {
    public DaprProperties {
        if (sidecarHost == null) sidecarHost = "localhost";
        if (sidecarHttpPort <= 0) sidecarHttpPort = 3500;
        if (sidecarGrpcPort <= 0) sidecarGrpcPort = 50001;
        if (pubsubName == null) pubsubName = "flowforge-pubsub";
        if (stateStoreName == null) stateStoreName = "flowforge-state";
        if (secretStoreName == null) secretStoreName = "flowforge-secrets";
        if (timeout == null) timeout = Duration.ofSeconds(30);
    }
}
```

### 29.2 Dapr client configuration

```java
@Configuration
@EnableConfigurationProperties(DaprProperties.class)
public class DaprClientConfig {

    @Bean
    public DaprClient daprClient(DaprProperties props) {
        // Dapr Java SDK reads DAPR_HTTP_PORT and DAPR_GRPC_PORT env vars automatically.
        // In AKS with Dapr sidecar injection, these are set by the Dapr operator.
        // For explicit configuration, set system properties before building:
        System.setProperty("dapr.sidecar.ip", props.sidecarHost());
        System.setProperty("dapr.http.port", String.valueOf(props.sidecarHttpPort()));
        System.setProperty("dapr.grpc.port", String.valueOf(props.sidecarGrpcPort()));
        return new DaprClientBuilder().build();
    }

    @Bean
    public DaprPreviewClient daprPreviewClient(DaprProperties props) {
        System.setProperty("dapr.sidecar.ip", props.sidecarHost());
        System.setProperty("dapr.http.port", String.valueOf(props.sidecarHttpPort()));
        System.setProperty("dapr.grpc.port", String.valueOf(props.sidecarGrpcPort()));
        return new DaprClientBuilder().buildPreviewClient();
    }
}
```

### 29.3 Service invocation wrapper

```java
@Service
public class DaprServiceInvoker {

    private static final Logger log = LoggerFactory.getLogger(DaprServiceInvoker.class);

    private final DaprClient daprClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * Invoke a method on another FlowForge service via Dapr service invocation.
     * Dapr handles service discovery, mTLS, retries, and load balancing.
     */
    public <T> T invoke(String appId, String method, Object request,
                         Class<T> responseType) {
        return meterRegistry.timer("flowforge.dapr.invoke.latency",
                "appId", appId, "method", method)
            .record(() -> {
                try {
                    var response = daprClient.invokeMethod(
                        appId, method, request,
                        HttpExtension.POST,
                        responseType
                    ).block(Duration.ofSeconds(30));

                    meterRegistry.counter("flowforge.dapr.invoke.success",
                        "appId", appId).increment();
                    return response;
                } catch (Exception e) {
                    meterRegistry.counter("flowforge.dapr.invoke.error",
                        "appId", appId).increment();
                    log.error("Dapr invocation failed: {}/{}", appId, method, e);
                    throw new DaprInvocationException(appId, method, e);
                }
            });
    }

    /**
     * Fire-and-forget invocation (returns immediately).
     */
    public void invokeAsync(String appId, String method, Object request) {
        daprClient.invokeMethod(appId, method, request, HttpExtension.POST)
            .subscribe(
                v -> log.debug("Async invocation sent: {}/{}", appId, method),
                e -> log.error("Async invocation failed: {}/{}", appId, method, e)
            );
    }
}
```

### 29.4 Pub/Sub event service

```java
@Service
public class DaprEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DaprEventPublisher.class);

    private final DaprClient daprClient;
    private final DaprProperties props;
    private final MeterRegistry meterRegistry;

    /**
     * Publish a pipeline event to a topic.
     */
    public void publish(String topic, PipelineEvent event) {
        try {
            daprClient.publishEvent(
                props.pubsubName(), topic,
                event,
                Map.of("content-type", "application/json",
                       "cloudevent.type", event.type(),
                       "cloudevent.source", "flowforge/" + event.stage())
            ).block(props.timeout());

            meterRegistry.counter("flowforge.dapr.pubsub.published", "topic", topic).increment();
            log.debug("Published event to {}: {}", topic, event.type());
        } catch (Exception e) {
            meterRegistry.counter("flowforge.dapr.pubsub.error", "topic", topic).increment();
            log.error("Failed to publish event to {}", topic, e);
            throw new DaprPublishException(topic, e);
        }
    }
}

/**
 * Event subscriber using Dapr's /subscribe endpoint integration.
 */
@RestController
public class DaprEventSubscriber {

    private static final Logger log = LoggerFactory.getLogger(DaprEventSubscriber.class);

    private final List<PipelineEventHandler> handlers;

    /**
     * Dapr calls this to discover subscriptions.
     */
    @GetMapping("/dapr/subscribe")
    public List<DaprSubscription> subscribe() {
        return List.of(
            new DaprSubscription("flowforge-pubsub", "pipeline.stage.completed",
                "/api/events/stage-completed"),
            new DaprSubscription("flowforge-pubsub", "pipeline.stage.failed",
                "/api/events/stage-failed"),
            new DaprSubscription("flowforge-pubsub", "pipeline.snapshot.ready",
                "/api/events/snapshot-ready")
        );
    }

    @PostMapping("/api/events/stage-completed")
    public ResponseEntity<Void> onStageCompleted(
            @RequestBody CloudEvent<StageCompletedEvent> event) {
        log.info("Stage completed: {} for snapshot {}",
            event.data().stage(), event.data().snapshotId());
        handlers.forEach(h -> h.onStageCompleted(event.data()));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/events/stage-failed")
    public ResponseEntity<Void> onStageFailed(
            @RequestBody CloudEvent<StageFailedEvent> event) {
        log.warn("Stage failed: {} - {}", event.data().stage(), event.data().error());
        handlers.forEach(h -> h.onStageFailed(event.data()));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/events/snapshot-ready")
    public ResponseEntity<Void> onSnapshotReady(
            @RequestBody CloudEvent<SnapshotReadyEvent> event) {
        log.info("Snapshot ready: {}", event.data().snapshotId());
        handlers.forEach(h -> h.onSnapshotReady(event.data()));
        return ResponseEntity.ok().build();
    }
}

// ── Event records ─────────────────────────────────────────

public sealed interface PipelineEvent {
    String type();
    String stage();

    record StageCompletedEvent(UUID snapshotId, String stage, Duration duration,
        Map<String, Object> metadata) implements PipelineEvent {
        public String type() { return "stage.completed"; }
    }

    record StageFailedEvent(UUID snapshotId, String stage, String error,
        int attempt) implements PipelineEvent {
        public String type() { return "stage.failed"; }
    }

    record SnapshotReadyEvent(UUID snapshotId, List<String> repoUrls,
        Instant createdAt) implements PipelineEvent {
        public String type() { return "snapshot.ready"; }
        public String stage() { return "orchestrator"; }
    }
}

public record DaprSubscription(String pubsubname, String topic, String route) {}

public record CloudEvent<T>(String id, String source, String type,
                              String specversion, T data) {}

public interface PipelineEventHandler {
    default void onStageCompleted(PipelineEvent.StageCompletedEvent event) {}
    default void onStageFailed(PipelineEvent.StageFailedEvent event) {}
    default void onSnapshotReady(PipelineEvent.SnapshotReadyEvent event) {}
}
```

### 29.5 State management service

```java
@Service
public class DaprStateService {

    private final DaprClient daprClient;
    private final DaprProperties props;
    private final ObjectMapper objectMapper;

    /**
     * Save pipeline state (e.g., stage progress, intermediate results).
     */
    public void saveState(String key, Object value) {
        daprClient.saveState(props.stateStoreName(), key, value)
            .block(props.timeout());
    }

    /**
     * Save with ETag for optimistic concurrency.
     */
    public void saveState(String key, Object value, String etag) {
        var stateOptions = new StateOptions(
            StateOptions.Consistency.STRONG,
            StateOptions.Concurrency.FIRST_WRITE
        );
        daprClient.saveState(props.stateStoreName(), key, etag, value, stateOptions)
            .block(props.timeout());
    }

    /**
     * Get pipeline state by key.
     */
    public <T> Optional<T> getState(String key, Class<T> type) {
        var state = daprClient.getState(props.stateStoreName(), key, type)
            .block(props.timeout());
        return Optional.ofNullable(state).map(State::getValue);
    }

    /**
     * Delete state.
     */
    public void deleteState(String key) {
        daprClient.deleteState(props.stateStoreName(), key)
            .block(props.timeout());
    }

    /**
     * Bulk state operations for batch updates.
     */
    public void saveBulkState(Map<String, Object> states) {
        var stateList = states.entrySet().stream()
            .map(e -> new State<>(e.getKey(), e.getValue(), ""))
            .toList();
        daprClient.saveBulkState(props.stateStoreName(), stateList)
            .block(props.timeout());
    }
}
```

### 29.6 Secret management service

```java
@Service
public class DaprSecretService {

    private static final Logger log = LoggerFactory.getLogger(DaprSecretService.class);

    private final DaprClient daprClient;
    private final DaprProperties props;

    /**
     * Get a secret from the configured secret store (Azure Key Vault).
     */
    public String getSecret(String secretName) {
        var secrets = daprClient.getSecret(props.secretStoreName(), secretName)
            .block(props.timeout());
        if (secrets == null || !secrets.containsKey(secretName)) {
            throw new IllegalStateException(
                "Secret '%s' not found in store '%s'"
                    .formatted(secretName, props.secretStoreName()));
        }
        return secrets.get(secretName);
    }

    /**
     * Get multiple secrets at once.
     */
    public Map<String, String> getSecrets(List<String> secretNames) {
        return secretNames.stream()
            .collect(Collectors.toMap(
                name -> name,
                this::getSecret
            ));
    }

    /**
     * Get all secrets (bulk).
     */
    public Map<String, Map<String, String>> getBulkSecret() {
        return daprClient.getBulkSecret(props.secretStoreName())
            .block(props.timeout());
    }
}

/**
 * Load database credentials and other secrets from Dapr secret store at startup
 * and inject them as Spring properties.
 *
 * Activate with: spring.profiles.active=dapr
 *
 * NOTE: This processor runs before the Spring context is fully initialized,
 * so it creates a temporary DaprClient directly rather than using the bean.
 */
/**
 * Registered via META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports
 * (not via @Component — runs before component scanning).
 */
public class DaprSecretPostProcessor implements EnvironmentPostProcessor {

    private static final Map<String, String> SECRET_TO_PROPERTY = Map.of(
        "postgres-password", "spring.datasource.password",
        "minio-secret-key", "flowforge.minio.secret-key",
        "neo4j-password", "flowforge.neo4j.password"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                        SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("dapr"))) {
            return;
        }
        try {
            var daprClient = new DaprClientBuilder().build();
            var secretStore = environment.getProperty(
                "flowforge.dapr.secret-store-name", "flowforge-secrets");

            var properties = new java.util.Properties();
            for (var entry : SECRET_TO_PROPERTY.entrySet()) {
                try {
                    var secrets = daprClient.getSecret(secretStore, entry.getKey())
                        .block(Duration.ofSeconds(10));
                    if (secrets != null && secrets.containsKey(entry.getKey())) {
                        properties.put(entry.getValue(), secrets.get(entry.getKey()));
                    }
                } catch (Exception e) {
                    // Log but don't fail startup — allows fallback to env vars
                    System.err.println("Dapr secret '%s' unavailable: %s"
                        .formatted(entry.getKey(), e.getMessage()));
                }
            }

            if (!properties.isEmpty()) {
                environment.getPropertySources().addFirst(
                    new org.springframework.core.env.PropertiesPropertySource(
                        "daprSecrets", properties));
            }
            daprClient.close();
        } catch (Exception e) {
            System.err.println("Dapr secret loading skipped: " + e.getMessage());
        }
    }
}
```

### 29.7 Dapr component YAML definitions

```yaml
# k8s/dapr/pubsub.yaml
apiVersion: dapr.io/v1alpha1
kind: Component
metadata:
  name: flowforge-pubsub
  namespace: flowforge
spec:
  type: pubsub.redis
  version: v1
  metadata:
    - name: redisHost
      value: flowforge-redis-master.flowforge-infra.svc.cluster.local:6379
    - name: redisPassword
      secretKeyRef:
        name: redis-secret
        key: password
---
# k8s/dapr/statestore.yaml
apiVersion: dapr.io/v1alpha1
kind: Component
metadata:
  name: flowforge-state
  namespace: flowforge
spec:
  type: state.redis
  version: v1
  metadata:
    - name: redisHost
      value: flowforge-redis-master.flowforge-infra.svc.cluster.local:6379
    - name: redisPassword
      secretKeyRef:
        name: redis-secret
        key: password
    - name: actorStateStore
      value: "true"
---
# k8s/dapr/secretstore.yaml
apiVersion: dapr.io/v1alpha1
kind: Component
metadata:
  name: flowforge-secrets
  namespace: flowforge
spec:
  type: secretstores.azure.keyvault
  version: v1
  metadata:
    - name: vaultName
      value: flowforge-kv
    - name: azureClientId
      value: ""  # Workload Identity
```

### 29.7a Dapr AKS deployment via ArgoCD

#### ArgoCD Application (multi-source: Helm chart + Git values)

```yaml
# k8s/argocd/apps/dapr.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: dapr
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "3"
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: flowforge
  sources:
    - repoURL: https://dapr.github.io/helm-charts
      chart: dapr
      targetRevision: 1.13.*
      helm:
        valueFiles:
          - $values/k8s/infrastructure/dapr/values.yaml
    - repoURL: https://github.com/tesco/flow-forge.git
      targetRevision: HEAD
      ref: values
  destination:
    server: https://kubernetes.default.svc
    namespace: dapr-system
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
# k8s/infrastructure/dapr/values.yaml
global:
  mtls:
    enabled: true
  logAsJson: true
  ha:
    enabled: true

dapr_operator:
  enabled: true
  resources:
    requests:
      cpu: "250m"
      memory: 256Mi
    limits:
      cpu: "500m"
      memory: 512Mi

dapr_sidecar_injector:
  enabled: true
  resources:
    requests:
      cpu: "100m"
      memory: 128Mi
    limits:
      cpu: "250m"
      memory: 256Mi

dapr_placement:
  enabled: true

dapr_sentry:
  enabled: true
```

#### Dapr Component manifests (deployed via ArgoCD)

The following Dapr Component manifests are deployed to the `flowforge` namespace by a separate ArgoCD Application that syncs the `k8s/dapr/` directory:

```yaml
# k8s/argocd/apps/dapr-components.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: dapr-components
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "5"
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: flowforge
  source:
    repoURL: https://github.com/tesco/flow-forge.git
    targetRevision: HEAD
    path: k8s/dapr
  destination:
    server: https://kubernetes.default.svc
    namespace: flowforge
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

**Pub/Sub component** (`k8s/dapr/pubsub.yaml`):

```yaml
apiVersion: dapr.io/v1alpha1
kind: Component
metadata:
  name: flowforge-pubsub
  namespace: flowforge
spec:
  type: pubsub.redis
  version: v1
  metadata:
    - name: redisHost
      value: flowforge-redis-master.flowforge-infra.svc.cluster.local:6379
    - name: redisPassword
      secretKeyRef:
        name: redis-secret
        key: password
```

**State store component** (`k8s/dapr/statestore.yaml`):

```yaml
apiVersion: dapr.io/v1alpha1
kind: Component
metadata:
  name: flowforge-state
  namespace: flowforge
spec:
  type: state.redis
  version: v1
  metadata:
    - name: redisHost
      value: flowforge-redis-master.flowforge-infra.svc.cluster.local:6379
    - name: redisPassword
      secretKeyRef:
        name: redis-secret
        key: password
    - name: actorStateStore
      value: "true"
```

**Secret store component** (`k8s/dapr/secretstore.yaml`):

```yaml
apiVersion: dapr.io/v1alpha1
kind: Component
metadata:
  name: flowforge-secrets
  namespace: flowforge
spec:
  type: secretstores.azure.keyvault
  version: v1
  metadata:
    - name: vaultName
      value: flowforge-kv
    - name: azureClientId
      value: ""  # Workload Identity — populated via Azure AD pod identity
```

### 29.8 Dapr health indicator

```java
@Component
public class DaprHealthIndicator implements HealthIndicator {

    private final DaprClient daprClient;

    @Override
    public Health health() {
        try {
            daprClient.waitForSidecar(5000).block(Duration.ofSeconds(10));
            return Health.up()
                .withDetail("sidecar", "ready")
                .build();
        } catch (Exception e) {
            return Health.down(e)
                .withDetail("sidecar", "not ready")
                .build();
        }
    }
}
```

### 29.9 Dependencies

```kotlin
// libs/dapr/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(libs.dapr.sdk)
    implementation(libs.dapr.sdk.springboot)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
}
```

Version catalog:
```toml
[versions]
dapr = "1.13.0"

[libraries]
dapr-sdk = { module = "io.dapr:dapr-sdk", version.ref = "dapr" }
dapr-sdk-springboot = { module = "io.dapr:dapr-sdk-springboot", version.ref = "dapr" }
```

## Testing & Verification Strategy

### 1. Unit Tests

All unit tests live in `libs/dapr/src/test/java/com/flowforge/dapr/`.

#### DaprServiceInvokerTest

Tests service invocation with mocked `DaprClient`, verifying success/error counter increments and timeout behavior.

```java
@ExtendWith(MockitoExtension.class)
class DaprServiceInvokerTest {

    @Mock DaprClient daprClient;
    @Mock ObjectMapper objectMapper;
    @Mock MeterRegistry meterRegistry;
    @InjectMocks DaprServiceInvoker invoker;

    @BeforeEach
    void setUp() {
        when(meterRegistry.timer(anyString(), any(String[].class)))
            .thenReturn(new SimpleTimer(Clock.SYSTEM));
        when(meterRegistry.counter(anyString(), any(String[].class)))
            .thenReturn(new NoopCounter(Counter.builder("noop")));
    }

    @Test
    @DisplayName("invoke calls DaprClient.invokeMethod with correct appId and method")
    void invoke_delegatesToDaprClient() {
        var expected = new OrderResponse("order-1", "CREATED");
        when(daprClient.invokeMethod(eq("order-service"), eq("createOrder"),
            any(), eq(HttpExtension.POST), eq(OrderResponse.class)))
            .thenReturn(Mono.just(expected));

        var result = invoker.invoke("order-service", "createOrder",
            new OrderRequest("item-1"), OrderResponse.class);

        assertThat(result.orderId()).isEqualTo("order-1");
        verify(daprClient).invokeMethod("order-service", "createOrder",
            any(), eq(HttpExtension.POST), eq(OrderResponse.class));
    }

    @Test
    @DisplayName("invoke throws DaprInvocationException on Dapr error")
    void invoke_throwsOnError() {
        when(daprClient.invokeMethod(anyString(), anyString(),
            any(), any(HttpExtension.class), any(Class.class)))
            .thenReturn(Mono.error(new RuntimeException("connection refused")));

        assertThatThrownBy(() ->
            invoker.invoke("dead-service", "method", "req", String.class))
            .isInstanceOf(DaprInvocationException.class);
    }

    @Test
    @DisplayName("invokeAsync does not block the caller")
    void invokeAsync_nonBlocking() {
        when(daprClient.invokeMethod(anyString(), anyString(),
            any(), any(HttpExtension.class)))
            .thenReturn(Mono.empty());

        assertThatCode(() ->
            invoker.invokeAsync("service", "method", "request"))
            .doesNotThrowAnyException();
    }
}
```

#### DaprEventPublisherTest

Validates CloudEvent publishing and topic routing.

```java
@ExtendWith(MockitoExtension.class)
class DaprEventPublisherTest {

    @Mock DaprClient daprClient;
    @Mock MeterRegistry meterRegistry;
    private DaprProperties props;
    private DaprEventPublisher publisher;

    @BeforeEach
    void setUp() {
        props = new DaprProperties(
            "localhost", 3500, 50001, "flowforge-pubsub",
            "flowforge-state", "flowforge-secrets", Duration.ofSeconds(5));
        publisher = new DaprEventPublisher(daprClient, props, meterRegistry);
        when(meterRegistry.counter(anyString(), any(String[].class)))
            .thenReturn(new NoopCounter(Counter.builder("noop")));
    }

    @Test
    @DisplayName("publish sends event to correct pubsub and topic")
    void publish_correctPubsubAndTopic() {
        when(daprClient.publishEvent(anyString(), anyString(), any(), anyMap()))
            .thenReturn(Mono.empty());

        var event = new PipelineEvent.StageCompletedEvent(
            UUID.randomUUID(), "parse-code", Duration.ofSeconds(30), Map.of());

        publisher.publish("pipeline.stage.completed", event);

        verify(daprClient).publishEvent(
            eq("flowforge-pubsub"),
            eq("pipeline.stage.completed"),
            eq(event),
            argThat(meta -> "application/json".equals(meta.get("content-type"))
                && "stage.completed".equals(meta.get("cloudevent.type"))));
    }

    @Test
    @DisplayName("publish throws DaprPublishException on failure")
    void publish_throwsOnFailure() {
        when(daprClient.publishEvent(anyString(), anyString(), any(), anyMap()))
            .thenReturn(Mono.error(new RuntimeException("pubsub unavailable")));

        assertThatThrownBy(() -> publisher.publish("topic",
            new PipelineEvent.StageCompletedEvent(
                UUID.randomUUID(), "stage", Duration.ZERO, Map.of())))
            .isInstanceOf(DaprPublishException.class);
    }
}
```

#### DaprEventSubscriberTest

Tests event handler dispatch via MockMvc.

```java
@WebMvcTest(DaprEventSubscriber.class)
class DaprEventSubscriberTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean List<PipelineEventHandler> handlers;

    @Test
    @DisplayName("GET /dapr/subscribe returns subscription list")
    void subscribe_returnsList() throws Exception {
        mockMvc.perform(get("/dapr/subscribe"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath("$[0].pubsubname").value("flowforge-pubsub"))
            .andExpect(jsonPath("$[0].topic").value("pipeline.stage.completed"));
    }

    @Test
    @DisplayName("POST /api/events/stage-completed dispatches to handlers")
    void onStageCompleted_dispatchesToHandlers() throws Exception {
        mockMvc.perform(post("/api/events/stage-completed")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id": "evt-1", "source": "flowforge/parse-code",
                     "type": "stage.completed", "specversion": "1.0",
                     "data": {"snapshotId": "550e8400-e29b-41d4-a716-446655440000",
                              "stage": "parse-code", "duration": "PT30S", "metadata": {}}}
                    """))
            .andExpect(status().isOk());

        verify(handlers).forEach(any());
    }
}
```

#### DaprStateServiceTest

Tests state CRUD operations and optimistic concurrency with ETag.

```java
@ExtendWith(MockitoExtension.class)
class DaprStateServiceTest {

    @Mock DaprClient daprClient;
    @Mock ObjectMapper objectMapper;
    private DaprProperties props;
    private DaprStateService stateService;

    @BeforeEach
    void setUp() {
        props = new DaprProperties(
            "localhost", 3500, 50001, "flowforge-pubsub",
            "flowforge-state", "flowforge-secrets", Duration.ofSeconds(5));
        stateService = new DaprStateService(daprClient, props, objectMapper);
    }

    @Test
    @DisplayName("saveState delegates to DaprClient with correct store name")
    void saveState_correctStoreName() {
        when(daprClient.saveState(anyString(), anyString(), any()))
            .thenReturn(Mono.empty());

        stateService.saveState("pipeline:snap-1:progress", Map.of("stage", "parse-code"));

        verify(daprClient).saveState(eq("flowforge-state"),
            eq("pipeline:snap-1:progress"), any());
    }

    @Test
    @DisplayName("getState returns Optional.empty when key not found")
    void getState_emptyWhenNotFound() {
        when(daprClient.getState("flowforge-state", "missing-key", String.class))
            .thenReturn(Mono.just(new State<>("missing-key", null, "")));

        var result = stateService.getState("missing-key", String.class);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("saveState with ETag uses FIRST_WRITE concurrency")
    void saveState_withEtag_usesFirstWrite() {
        when(daprClient.saveState(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(Mono.empty());

        stateService.saveState("key", "value", "etag-1");

        verify(daprClient).saveState(eq("flowforge-state"), eq("key"), eq("etag-1"),
            eq("value"), argThat(opts ->
                opts.getConcurrency() == StateOptions.Concurrency.FIRST_WRITE));
    }

    @Test
    @DisplayName("deleteState removes state by key")
    void deleteState_removesKey() {
        when(daprClient.deleteState(anyString(), anyString()))
            .thenReturn(Mono.empty());

        stateService.deleteState("key-to-remove");

        verify(daprClient).deleteState("flowforge-state", "key-to-remove");
    }

    @Test
    @DisplayName("saveBulkState sends all entries in one call")
    void saveBulkState_batchOperation() {
        when(daprClient.saveBulkState(anyString(), anyList()))
            .thenReturn(Mono.empty());

        stateService.saveBulkState(Map.of("k1", "v1", "k2", "v2"));

        verify(daprClient).saveBulkState(eq("flowforge-state"),
            argThat(list -> list.size() == 2));
    }
}
```

#### DaprSecretServiceTest

Tests secret retrieval and the `IllegalStateException` on missing secrets.

```java
@ExtendWith(MockitoExtension.class)
class DaprSecretServiceTest {

    @Mock DaprClient daprClient;
    private DaprProperties props;
    private DaprSecretService secretService;

    @BeforeEach
    void setUp() {
        props = new DaprProperties(
            "localhost", 3500, 50001, "flowforge-pubsub",
            "flowforge-state", "flowforge-secrets", Duration.ofSeconds(5));
        secretService = new DaprSecretService(daprClient, props);
    }

    @Test
    @DisplayName("getSecret returns value when secret exists")
    void getSecret_returnsValue() {
        when(daprClient.getSecret("flowforge-secrets", "db-password"))
            .thenReturn(Mono.just(Map.of("db-password", "s3cret")));

        var result = secretService.getSecret("db-password");
        assertThat(result).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("getSecret throws IllegalStateException when secret not found")
    void getSecret_throwsWhenMissing() {
        when(daprClient.getSecret("flowforge-secrets", "missing"))
            .thenReturn(Mono.just(Map.of()));

        assertThatThrownBy(() -> secretService.getSecret("missing"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Secret 'missing' not found");
    }

    @Test
    @DisplayName("getSecrets retrieves multiple secrets")
    void getSecrets_multipleKeys() {
        when(daprClient.getSecret(eq("flowforge-secrets"), anyString()))
            .thenAnswer(inv -> {
                String key = inv.getArgument(1);
                return Mono.just(Map.of(key, key + "-value"));
            });

        var secrets = secretService.getSecrets(List.of("key1", "key2"));
        assertThat(secrets).containsEntry("key1", "key1-value")
            .containsEntry("key2", "key2-value");
    }
}
```

#### DaprHealthIndicatorTest

```java
@ExtendWith(MockitoExtension.class)
class DaprHealthIndicatorTest {

    @Mock DaprClient daprClient;
    @InjectMocks DaprHealthIndicator indicator;

    @Test
    @DisplayName("Reports UP when sidecar is ready")
    void healthUp() {
        when(daprClient.waitForSidecar(5000)).thenReturn(Mono.empty());
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("Reports DOWN when sidecar is not ready")
    void healthDown() {
        when(daprClient.waitForSidecar(5000))
            .thenReturn(Mono.error(new RuntimeException("sidecar not ready")));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
```

### 2. Integration Tests

#### Dapr state management with Testcontainers Redis

Uses a Redis container to test real state CRUD operations through the Dapr SDK (without the Dapr sidecar — direct Redis access validates state serialization).

```java
@SpringBootTest
@Tag("integration")
@Testcontainers
@TestPropertySource(properties = {
    "flowforge.dapr.sidecar-host=localhost",
    "flowforge.dapr.state-store-name=flowforge-state"
})
class DaprStateIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired DaprStateService stateService;
    @MockitoBean DaprClient daprClient;

    @Test
    @DisplayName("State round-trip: save → get → delete → get returns empty")
    void stateRoundTrip() {
        when(daprClient.saveState(anyString(), anyString(), any()))
            .thenReturn(Mono.empty());
        when(daprClient.getState(anyString(), eq("test-key"), eq(String.class)))
            .thenReturn(Mono.just(new State<>("test-key", "test-value", "1")));
        when(daprClient.deleteState(anyString(), anyString()))
            .thenReturn(Mono.empty());

        stateService.saveState("test-key", "test-value");
        var retrieved = stateService.getState("test-key", String.class);
        assertThat(retrieved).isPresent().hasValue("test-value");

        stateService.deleteState("test-key");
        when(daprClient.getState(anyString(), eq("test-key"), eq(String.class)))
            .thenReturn(Mono.just(new State<>("test-key", null, "")));
        var deleted = stateService.getState("test-key", String.class);
        assertThat(deleted).isEmpty();
    }
}
```

#### CloudEvent format validation

```java
@Tag("integration")
class CloudEventFormatTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @Test
    @DisplayName("StageCompletedEvent serializes as valid CloudEvent JSON")
    void stageCompleted_validCloudEvent() throws Exception {
        var event = new CloudEvent<>(
            UUID.randomUUID().toString(),
            "flowforge/parse-code",
            "stage.completed",
            "1.0",
            new PipelineEvent.StageCompletedEvent(
                UUID.randomUUID(), "parse-code",
                Duration.ofSeconds(45), Map.of("files", 120)));

        var json = objectMapper.writeValueAsString(event);
        var node = objectMapper.readTree(json);

        assertThat(node.has("id")).isTrue();
        assertThat(node.get("specversion").asText()).isEqualTo("1.0");
        assertThat(node.get("type").asText()).isEqualTo("stage.completed");
        assertThat(node.get("data").has("snapshotId")).isTrue();
        assertThat(node.get("data").get("stage").asText()).isEqualTo("parse-code");
    }
}
```

### 3. Test Fixtures & Sample Data

| Fixture file | Description |
|---|---|
| `src/test/resources/dapr/cloud-event-stage-completed.json` | Sample CloudEvent payload for `stage.completed` topic |
| `src/test/resources/dapr/cloud-event-stage-failed.json` | Sample CloudEvent payload for `stage.failed` with error details |
| `src/test/resources/dapr/cloud-event-snapshot-ready.json` | Sample CloudEvent payload for `snapshot.ready` event |
| `src/test/resources/dapr/state-pipeline-progress.json` | Sample state object for pipeline progress tracking |
| `src/test/resources/dapr/subscription-list.json` | Expected Dapr subscription discovery response |
| `k8s/dapr/pubsub.yaml` | Component YAML used for YAML validation tests |
| `k8s/dapr/statestore.yaml` | Component YAML used for YAML validation tests |
| `k8s/dapr/secretstore.yaml` | Component YAML used for YAML validation tests |

### 4. Mocking Strategy

| Dependency | Strategy | Rationale |
|---|---|---|
| `DaprClient` | **Mockito** (`@Mock`) | Dapr SDK calls require a running sidecar; mock all `invokeMethod`, `publishEvent`, `saveState`, `getState`, `getSecret` calls |
| `DaprPreviewClient` | **Mockito** (`@Mock`) | Preview API features tested independently |
| `PipelineEventHandler` | **Mockito** (`@MockitoBean` list) | Verify handler dispatch without real event processing |
| `MeterRegistry` | **`SimpleMeterRegistry`** or Mockito | Verify counter/timer increments |
| `ObjectMapper` | **Real instance** | JSON/CloudEvent serialization correctness must be validated |
| Redis (for state tests) | **Testcontainers** `redis:7-alpine` | Validates state serialization format; used only in `@Tag("integration")` tests |
| Dapr sidecar | **Never used in tests** | All tests mock `DaprClient` directly; sidecar-dependent behavior is covered by the health indicator mock |

### 5. CI/CD Considerations

- **Test tags**: `@Tag("unit")` for Mockito-only tests, `@Tag("integration")` for Spring context / Testcontainers tests
- **Gradle task separation**:
  ```kotlin
  tasks.test { useJUnitPlatform { includeTags("unit") } }
  tasks.register<Test>("integrationTest") { useJUnitPlatform { includeTags("integration") } }
  ```
- **Docker requirement**: Only `@Tag("integration")` tests that use Testcontainers Redis require Docker; all unit tests are Docker-free
- **Dapr sidecar not required**: Tests mock `DaprClient` directly — no Dapr sidecar container needed in CI
- **Profile isolation**: `DaprSecretPostProcessor` only activates with `@Profile("dapr")`; tests use `@ActiveProfiles("test")` to avoid triggering secret loading at startup
- **Component YAML validation**: Add a simple YAML parse test to validate `k8s/dapr/*.yaml` files are well-formed:
  ```java
  @ParameterizedTest
  @ValueSource(strings = {"pubsub.yaml", "statestore.yaml", "secretstore.yaml"})
  void componentYamlParseable(String filename) throws Exception {
      var yaml = new ObjectMapper(new YAMLFactory());
      var doc = yaml.readValue(Path.of("k8s/dapr/" + filename).toFile(), Map.class);
      assertThat(doc).containsKey("apiVersion");
      assertThat(doc.get("apiVersion").toString()).startsWith("dapr.io/");
  }
  ```
- **Test execution time**: Target < 10s for unit tests, < 30s for integration tests with Redis container

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| Dapr sidecar | DaprHealthIndicator | Health UP |
| Service invoke | Call parser from orchestrator | Response received |
| Publish event | Publish stage.completed | Event in topic |
| Subscribe event | Send to /api/events/stage-completed | Handler invoked |
| Save state | saveState("test", data) | State stored |
| Get state | getState("test") | Data retrieved |
| Delete state | deleteState("test") | State removed |
| Bulk state | saveBulkState(Map) | All saved |
| Get secret | getSecret("db-password") | Secret value returned |
| Bulk secrets | getBulkSecret() | All secrets returned |
| mTLS | Check Dapr dashboard | Encrypted communication |
| Retry | Kill target, restart | Auto-retry succeeds |

## Files to create

- `libs/dapr/build.gradle.kts`
- `libs/dapr/src/main/java/com/flowforge/dapr/config/DaprProperties.java`
- `libs/dapr/src/main/java/com/flowforge/dapr/config/DaprClientConfig.java`
- `libs/dapr/src/main/java/com/flowforge/dapr/service/DaprServiceInvoker.java`
- `libs/dapr/src/main/java/com/flowforge/dapr/event/DaprEventPublisher.java`
- `libs/dapr/src/main/java/com/flowforge/dapr/event/DaprEventSubscriber.java`
- `libs/dapr/src/main/java/com/flowforge/dapr/event/PipelineEvent.java`
- `libs/dapr/src/main/java/com/flowforge/dapr/state/DaprStateService.java`
- `libs/dapr/src/main/java/com/flowforge/dapr/secret/DaprSecretService.java`
- `libs/dapr/src/main/java/com/flowforge/dapr/health/DaprHealthIndicator.java`
- `libs/dapr/src/main/resources/META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports`
- `k8s/dapr/pubsub.yaml`
- `k8s/dapr/statestore.yaml`
- `k8s/dapr/secretstore.yaml`
- `libs/dapr/src/test/java/.../DaprServiceInvokerTest.java`
- `k8s/argocd/apps/dapr.yaml`
- `k8s/argocd/apps/dapr-components.yaml`
- `k8s/infrastructure/dapr/values.yaml`
- `k8s/dapr/pubsub.yaml`
- `k8s/dapr/statestore.yaml`
- `k8s/dapr/secretstore.yaml`

## Depends on

- Stage 04 (Spring Boot application foundation)
- Dapr runtime installed on AKS

## Produces

- Service-to-service invocation via Dapr (mTLS, retries, discovery)
- Pub/Sub event system for stage lifecycle notifications
- State management for pipeline progress tracking
- Secret management via Azure Key Vault through Dapr
- CloudEvent-based event model
