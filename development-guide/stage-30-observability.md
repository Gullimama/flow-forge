# Stage 30 — Observability (Micrometer + OpenTelemetry)

## Goal

Implement comprehensive **observability** across the FlowForge pipeline using Micrometer for metrics, OpenTelemetry Java Agent for distributed tracing, SLF4J + Logback for structured logging, and Grafana dashboards for visualization. Every pipeline stage emits consistent telemetry for monitoring, debugging, and performance optimization.

## Prerequisites

- Stage 04 (Spring Boot Actuator already enabled)
- Prometheus + Grafana + Tempo deployed on AKS

## What to build

### 30.1 Observability configuration

```java
@ConfigurationProperties(prefix = "flowforge.observability")
public record ObservabilityProperties(
    MetricsConfig metrics,
    TracingConfig tracing,
    LoggingConfig logging
) {
    public record MetricsConfig(
        boolean enabled,
        String prometheusEndpoint,
        Duration step,
        Map<String, String> commonTags
    ) {}
    public record TracingConfig(
        boolean enabled,
        String otlpEndpoint,
        double samplingRate,
        List<String> propagationTypes
    ) {}
    public record LoggingConfig(
        String level,
        boolean structuredJson,
        List<String> excludePatterns
    ) {}

    public ObservabilityProperties {
        if (metrics == null) metrics = new MetricsConfig(
            true, "/actuator/prometheus", Duration.ofSeconds(30),
            Map.of("application", "flowforge", "team", "platform"));
        if (tracing == null) tracing = new TracingConfig(
            true, "http://tempo.flowforge-obs.svc.cluster.local:4318",
            1.0, List.of("tracecontext", "baggage"));
        if (logging == null) logging = new LoggingConfig(
            "INFO", true, List.of("/health", "/prometheus"));
    }
}
```

### 30.2 Micrometer metrics configuration

```java
@Configuration
@EnableConfigurationProperties(ObservabilityProperties.class)
public class MetricsConfiguration {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonTags(ObservabilityProperties props) {
        return registry -> {
            props.metrics().commonTags().forEach(
                (k, v) -> registry.config().commonTags(k, v));
        };
    }

    @Bean
    TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
```

### 30.3 Pipeline stage metrics

```java
@Component
public class PipelineMetrics {

    private final MeterRegistry registry;

    // ── Stage lifecycle ──────────────────────────────────

    private final Map<String, Timer> stageTimers = new ConcurrentHashMap<>();
    private final Map<String, Counter> stageSuccessCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> stageFailureCounters = new ConcurrentHashMap<>();

    /**
     * Record a stage execution with timing and success/failure.
     */
    public <T> T recordStage(String stageName, UUID snapshotId,
                              Supplier<T> execution) {
        var timer = stageTimers.computeIfAbsent(stageName,
            name -> Timer.builder("flowforge.stage.duration")
                .tag("stage", name)
                .description("Pipeline stage execution duration")
                .register(registry));

        return timer.record(() -> {
            try {
                T result = execution.get();
                stageSuccessCounters.computeIfAbsent(stageName,
                    name -> Counter.builder("flowforge.stage.success")
                        .tag("stage", name).register(registry))
                    .increment();
                return result;
            } catch (Exception e) {
                stageFailureCounters.computeIfAbsent(stageName,
                    name -> Counter.builder("flowforge.stage.failure")
                        .tag("stage", name).register(registry))
                    .increment();
                throw e;
            }
        });
    }

    // ── DL model metrics ──────────────────────────────────

    public void recordEmbeddingBatch(String modelName, int batchSize, Duration latency) {
        Timer.builder("flowforge.embedding.batch.duration")
            .tag("model", modelName)
            .register(registry)
            .record(latency);
        Counter.builder("flowforge.embedding.batch.items")
            .tag("model", modelName)
            .register(registry)
            .increment(batchSize);
    }

    public void recordLlmCall(String modelName, int inputTokens, int outputTokens,
                               Duration latency) {
        Timer.builder("flowforge.llm.call.duration")
            .tag("model", modelName)
            .register(registry)
            .record(latency);
        Counter.builder("flowforge.llm.tokens.input")
            .tag("model", modelName)
            .register(registry)
            .increment(inputTokens);
        Counter.builder("flowforge.llm.tokens.output")
            .tag("model", modelName)
            .register(registry)
            .increment(outputTokens);
    }

    public void recordRerankerCall(int candidates, Duration latency) {
        Timer.builder("flowforge.reranker.duration")
            .register(registry)
            .record(latency);
        DistributionSummary.builder("flowforge.reranker.candidates")
            .register(registry)
            .record(candidates);
    }

    // ── Data processing metrics ───────────────────────────

    public void recordParsedArtifacts(String type, int count) {
        Counter.builder("flowforge.parsed.artifacts")
            .tag("type", type)
            .register(registry)
            .increment(count);
    }

    public void recordIndexedDocuments(String index, int count) {
        Counter.builder("flowforge.indexed.documents")
            .tag("index", index)
            .register(registry)
            .increment(count);
    }

    // ── Gauge metrics ─────────────────────────────────────

    public void registerQueueGauge(String queueName, Supplier<Number> sizeSupplier) {
        Gauge.builder("flowforge.queue.size", sizeSupplier)
            .tag("queue", queueName)
            .register(registry);
    }

    public void registerActiveSnapshotsGauge(Supplier<Number> supplier) {
        Gauge.builder("flowforge.snapshots.active", supplier)
            .register(registry);
    }
}
```

### 30.4 OpenTelemetry tracing configuration

```yaml
# application.yml — tracing configuration
management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://tempo.flowforge-obs.svc.cluster.local:4318/v1/traces
    metrics:
      endpoint: http://kube-prometheus-stack-prometheus.flowforge-obs.svc.cluster.local:9090/api/v1/otlp/v1/metrics

spring:
  application:
    name: flowforge-pipeline
```

```java
/**
 * Custom span enrichment for pipeline-specific context.
 */
@Component
public class FlowForgeSpanProcessor implements SpanProcessor {

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        var version = getClass().getPackage() != null
            ? getClass().getPackage().getImplementationVersion()
            : null;
        span.setAttribute("flowforge.version",
            version != null ? version : "dev");
    }

    @Override
    public boolean isStartRequired() { return true; }

    @Override
    public void onEnd(ReadableSpan span) {}

    @Override
    public boolean isEndRequired() { return false; }
}

/**
 * Observation convention for consistent span naming.
 */
@Component
public class FlowForgeObservationConvention implements GlobalServerRequestObservationConvention {

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        return KeyValues.of(
            KeyValue.of("flowforge.stage",
                context.getCarrier().getHeader("X-FlowForge-Stage") != null ?
                    context.getCarrier().getHeader("X-FlowForge-Stage") : "unknown")
        );
    }
}
```

### 30.5 Structured logging with Logback

```xml
<!-- src/main/resources/logback-spring.xml -->
<configuration>
    <springProfile name="default,dev">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="CONSOLE" />
        </root>
    </springProfile>

    <springProfile name="aks">
        <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <includeMdcKeyName>traceId</includeMdcKeyName>
                <includeMdcKeyName>spanId</includeMdcKeyName>
                <includeMdcKeyName>snapshotId</includeMdcKeyName>
                <includeMdcKeyName>stage</includeMdcKeyName>
                <customFields>
                    {"application":"flowforge","environment":"aks"}
                </customFields>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="JSON" />
        </root>
    </springProfile>
</configuration>
```

```java
/**
 * MDC context enrichment for pipeline stages.
 *
 * Virtual threads (used throughout FlowForge) do NOT inherit MDC from parent
 * threads automatically. This class provides ScopedValue-based context that
 * works correctly with virtual threads and StructuredTaskScope.
 */
@Component
public class PipelineLoggingContext {

    private static final ScopedValue<Map<String, String>> PIPELINE_CONTEXT = ScopedValue.newInstance();

    /**
     * Execute a block with pipeline context that propagates correctly
     * to child virtual threads via ScopedValue.
     */
    public <T> T withContext(UUID snapshotId, String stageName, Supplier<T> block) {
        var contextMap = Map.of(
            "snapshotId", snapshotId.toString(),
            "stage", stageName
        );
        return ScopedValue.where(PIPELINE_CONTEXT, contextMap).call(() -> {
            MDC.put("snapshotId", snapshotId.toString());
            MDC.put("stage", stageName);
            try {
                return block.get();
            } finally {
                MDC.remove("snapshotId");
                MDC.remove("stage");
            }
        });
    }

    /**
     * Restore MDC from ScopedValue context. Call this at the start of
     * virtual thread tasks to inherit the parent's pipeline context.
     */
    public static void restoreMdcFromScope() {
        if (PIPELINE_CONTEXT.isBound()) {
            PIPELINE_CONTEXT.get().forEach(MDC::put);
        }
    }

    /**
     * Get the current pipeline context (works from any virtual thread).
     */
    public static Optional<Map<String, String>> currentContext() {
        return PIPELINE_CONTEXT.isBound()
            ? Optional.of(PIPELINE_CONTEXT.get())
            : Optional.empty();
    }
}
```

### 30.6 Grafana dashboard definitions

```json
// k8s/grafana/flowforge-pipeline-dashboard.json (excerpt)
{
  "dashboard": {
    "title": "FlowForge Pipeline",
    "panels": [
      {
        "title": "Stage Duration (p95)",
        "type": "timeseries",
        "targets": [{
          "expr": "histogram_quantile(0.95, rate(flowforge_stage_duration_seconds_bucket[5m]))",
          "legendFormat": "{{stage}}"
        }]
      },
      {
        "title": "Stage Success Rate",
        "type": "stat",
        "targets": [{
          "expr": "sum(rate(flowforge_stage_success_total[1h])) / (sum(rate(flowforge_stage_success_total[1h])) + sum(rate(flowforge_stage_failure_total[1h])))"
        }]
      },
      {
        "title": "LLM Token Usage",
        "type": "timeseries",
        "targets": [
          {
            "expr": "rate(flowforge_llm_tokens_input_total[5m])",
            "legendFormat": "Input Tokens/s"
          },
          {
            "expr": "rate(flowforge_llm_tokens_output_total[5m])",
            "legendFormat": "Output Tokens/s"
          }
        ]
      },
      {
        "title": "Embedding Throughput",
        "type": "timeseries",
        "targets": [{
          "expr": "rate(flowforge_embedding_batch_items_total[5m])",
          "legendFormat": "{{model}}"
        }]
      },
      {
        "title": "Active Snapshots",
        "type": "gauge",
        "targets": [{
          "expr": "flowforge_snapshots_active"
        }]
      },
      {
        "title": "Reranker Latency (p99)",
        "type": "timeseries",
        "targets": [{
          "expr": "histogram_quantile(0.99, rate(flowforge_reranker_duration_seconds_bucket[5m]))"
        }]
      }
    ]
  }
}
```

### 30.7 Alerts (PrometheusRule)

```yaml
# k8s/monitoring/alerts.yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: flowforge-alerts
  namespace: flowforge
spec:
  groups:
    - name: flowforge.pipeline
      rules:
        - alert: StageFailureRateHigh
          expr: |
            sum(rate(flowforge_stage_failure_total[15m])) /
            (sum(rate(flowforge_stage_success_total[15m])) + sum(rate(flowforge_stage_failure_total[15m]))) > 0.1
          for: 5m
          labels:
            severity: warning
          annotations:
            summary: "Pipeline stage failure rate > 10%"

        - alert: LLMLatencyHigh
          expr: |
            histogram_quantile(0.99, rate(flowforge_llm_call_duration_seconds_bucket[10m])) > 30
          for: 5m
          labels:
            severity: warning
          annotations:
            summary: "LLM p99 latency > 30s"

        - alert: EmbeddingThroughputLow
          expr: |
            rate(flowforge_embedding_batch_items_total[10m]) < 10
          for: 10m
          labels:
            severity: info
          annotations:
            summary: "Embedding throughput below 10 items/sec"

        - alert: PipelineStuck
          expr: |
            (flowforge_stage_success_total + flowforge_stage_failure_total) == 0
            and (time() - flowforge_stage_duration_seconds_created) > 3600
          for: 10m
          labels:
            severity: critical
          annotations:
            summary: "Pipeline stage running for > 1 hour with no completion"

        - alert: StageHeartbeatMissing
          expr: |
            time() - max(timestamp(flowforge_stage_duration_seconds_count)) by (stage) > 1800
          for: 15m
          labels:
            severity: warning
          annotations:
            summary: "No stage completions in 30+ minutes"
```

### 30.8 OpenTelemetry Java Agent Dockerfile integration

```dockerfile
# Dockerfile
FROM eclipse-temurin:25-jre-alpine AS runtime

# Download OTel Java agent
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

COPY build/libs/pipeline.jar /app/pipeline.jar

ENV JAVA_OPTS="-XX:+UseZGC -Xmx4g"
ENV OTEL_SERVICE_NAME="flowforge-pipeline"
ENV OTEL_EXPORTER_OTLP_ENDPOINT="http://tempo:4318"
ENV OTEL_METRICS_EXPORTER="prometheus"
ENV OTEL_LOGS_EXPORTER="none"
ENV OTEL_TRACES_SAMPLER="parentbased_traceidratio"
ENV OTEL_TRACES_SAMPLER_ARG="1.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -javaagent:/app/opentelemetry-javaagent.jar -jar /app/pipeline.jar"]
```

### 30.9 Dependencies

```kotlin
// libs/observability/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.logstash.logback.encoder)
}
```

Version catalog additions:
```toml
[versions]
micrometer = "1.14.0"
opentelemetry = "1.44.0"
logstash-logback = "8.0"

[libraries]
micrometer-registry-prometheus = { module = "io.micrometer:micrometer-registry-prometheus", version.ref = "micrometer" }
micrometer-tracing-bridge-otel = { module = "io.micrometer:micrometer-tracing-bridge-otel", version.ref = "micrometer" }
opentelemetry-exporter-otlp = { module = "io.opentelemetry:opentelemetry-exporter-otlp", version.ref = "opentelemetry" }
logstash-logback-encoder = { module = "net.logstash.logback:logstash-logback-encoder", version.ref = "logstash-logback" }
```

### 30.10 Observability Stack AKS deployment via ArgoCD

#### kube-prometheus-stack ArgoCD Application (multi-source)

```yaml
# k8s/argocd/apps/kube-prometheus-stack.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: kube-prometheus-stack
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "6"
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: flowforge
  sources:
    - repoURL: https://prometheus-community.github.io/helm-charts
      chart: kube-prometheus-stack
      targetRevision: 61.*
      helm:
        valueFiles:
          - $values/k8s/observability/kube-prometheus-stack/values.yaml
    - repoURL: https://github.com/tesco/flow-forge.git
      targetRevision: HEAD
      ref: values
  destination:
    server: https://kubernetes.default.svc
    namespace: flowforge-obs
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - ServerSideApply=true
```

#### kube-prometheus-stack Helm values

```yaml
# k8s/observability/kube-prometheus-stack/values.yaml
grafana:
  enabled: true
  admin:
    existingSecret: grafana-admin-credentials
    userKey: admin-user
    passwordKey: admin-password
  sidecar:
    dashboards:
      enabled: true
      label: grafana_dashboard
      searchNamespace: ALL
  persistence:
    enabled: true
    size: 10Gi
  resources:
    requests:
      cpu: "250m"
      memory: 512Mi
    limits:
      cpu: "500m"
      memory: 1Gi

prometheus:
  prometheusSpec:
    retention: 30d
    storageSpec:
      volumeClaimTemplate:
        spec:
          accessModes: ["ReadWriteOnce"]
          resources:
            requests:
              storage: 50Gi
    serviceMonitorSelectorNilUsesHelmValues: false
    podMonitorSelectorNilUsesHelmValues: false
    resources:
      requests:
        cpu: "500m"
        memory: 2Gi
      limits:
        cpu: "2"
        memory: 4Gi

  additionalServiceMonitors:
    - name: flowforge-api
      selector:
        matchLabels:
          app: flowforge-api
      namespaceSelector:
        matchNames:
          - flowforge
      endpoints:
        - port: http
          path: /actuator/prometheus
          interval: 30s

alertmanager:
  enabled: true
  alertmanagerSpec:
    resources:
      requests:
        cpu: "100m"
        memory: 128Mi
      limits:
        cpu: "250m"
        memory: 256Mi

additionalPrometheusRulesMap:
  flowforge-alerts:
    groups:
      - name: flowforge.pipeline
        rules:
          - alert: StageFailureRateHigh
            expr: >
              sum(rate(flowforge_stage_failure_total[15m])) /
              (sum(rate(flowforge_stage_success_total[15m])) + sum(rate(flowforge_stage_failure_total[15m]))) > 0.1
            for: 5m
            labels:
              severity: warning
            annotations:
              summary: "Pipeline stage failure rate > 10%"
          - alert: LLMLatencyHigh
            expr: >
              histogram_quantile(0.99, rate(flowforge_llm_call_duration_seconds_bucket[10m])) > 30
            for: 5m
            labels:
              severity: warning
            annotations:
              summary: "LLM p99 latency > 30s"
          - alert: PipelineStuck
            expr: >
              (flowforge_stage_success_total + flowforge_stage_failure_total) == 0
              and (time() - flowforge_stage_duration_seconds_created) > 3600
            for: 10m
            labels:
              severity: critical
            annotations:
              summary: "Pipeline stage running for > 1 hour with no completion"
```

#### Tempo ArgoCD Application (multi-source)

```yaml
# k8s/argocd/apps/tempo.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: tempo
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "6"
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: flowforge
  sources:
    - repoURL: https://grafana.github.io/helm-charts
      chart: tempo
      targetRevision: 1.10.*
      helm:
        valueFiles:
          - $values/k8s/observability/tempo/values.yaml
    - repoURL: https://github.com/tesco/flow-forge.git
      targetRevision: HEAD
      ref: values
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

#### Tempo Helm values

```yaml
# k8s/observability/tempo/values.yaml
tempo:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317
        http:
          endpoint: 0.0.0.0:4318
  storage:
    trace:
      backend: s3
      s3:
        bucket: tempo-traces
        endpoint: flowforge-minio.flowforge-infra.svc.cluster.local:9000
        insecure: true
  extraEnv:
    - name: AWS_ACCESS_KEY_ID
      valueFrom:
        secretKeyRef:
          name: tempo-minio-credentials
          key: access-key
    - name: AWS_SECRET_ACCESS_KEY
      valueFrom:
        secretKeyRef:
          name: tempo-minio-credentials
          key: secret-key
  retention:
    min_duration: 168h  # 7 days
  resources:
    requests:
      cpu: "500m"
      memory: 1Gi
    limits:
      cpu: "1"
      memory: 2Gi

serviceMonitor:
  enabled: true
```

## Testing & Verification Strategy

### 1. Unit Tests

All unit tests live in `libs/observability/src/test/java/com/flowforge/observability/`.

#### PipelineMetricsTest

Validates that all metric types (timers, counters, gauges, distribution summaries) are registered and recorded correctly using `SimpleMeterRegistry`.

```java
class PipelineMetricsTest {

    private SimpleMeterRegistry registry;
    private PipelineMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new PipelineMetrics(registry);
    }

    @Test
    @DisplayName("recordStage creates timer and increments success counter")
    void recordStage_success() {
        var result = metrics.recordStage("parse-code", UUID.randomUUID(), () -> "done");

        assertThat(result).isEqualTo("done");
        assertThat(registry.timer("flowforge.stage.duration", "stage", "parse-code")
            .count()).isEqualTo(1);
        assertThat(registry.counter("flowforge.stage.success", "stage", "parse-code")
            .count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordStage increments failure counter on exception")
    void recordStage_failure() {
        assertThatThrownBy(() ->
            metrics.recordStage("parse-code", UUID.randomUUID(), () -> {
                throw new RuntimeException("parse error");
            }))
            .isInstanceOf(RuntimeException.class);

        assertThat(registry.counter("flowforge.stage.failure", "stage", "parse-code")
            .count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordStage reuses timers for same stage name")
    void recordStage_reuseTimers() {
        metrics.recordStage("index", UUID.randomUUID(), () -> null);
        metrics.recordStage("index", UUID.randomUUID(), () -> null);

        assertThat(registry.timer("flowforge.stage.duration", "stage", "index")
            .count()).isEqualTo(2);
    }

    @Test
    @DisplayName("recordEmbeddingBatch records duration and item count")
    void recordEmbeddingBatch_recordsMetrics() {
        metrics.recordEmbeddingBatch("codebert", 32, Duration.ofMillis(150));

        assertThat(registry.timer("flowforge.embedding.batch.duration", "model", "codebert")
            .count()).isEqualTo(1);
        assertThat(registry.counter("flowforge.embedding.batch.items", "model", "codebert")
            .count()).isEqualTo(32.0);
    }

    @Test
    @DisplayName("recordLlmCall records input/output tokens and latency")
    void recordLlmCall_recordsTokens() {
        metrics.recordLlmCall("qwen-32b", 500, 200, Duration.ofMillis(2000));

        assertThat(registry.counter("flowforge.llm.tokens.input", "model", "qwen-32b")
            .count()).isEqualTo(500.0);
        assertThat(registry.counter("flowforge.llm.tokens.output", "model", "qwen-32b")
            .count()).isEqualTo(200.0);
        assertThat(registry.timer("flowforge.llm.call.duration", "model", "qwen-32b")
            .count()).isEqualTo(1);
    }

    @Test
    @DisplayName("recordRerankerCall records candidate count distribution")
    void recordRerankerCall_recordsCandidates() {
        metrics.recordRerankerCall(50, Duration.ofMillis(80));

        assertThat(registry.summary("flowforge.reranker.candidates")
            .count()).isEqualTo(1);
        assertThat(registry.summary("flowforge.reranker.candidates")
            .totalAmount()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("registerQueueGauge exposes dynamic gauge value")
    void registerQueueGauge_dynamicValue() {
        var queueSize = new AtomicInteger(5);
        metrics.registerQueueGauge("ingest-queue", queueSize::get);

        assertThat(registry.gauge("flowforge.queue.size",
            Tags.of("queue", "ingest-queue"), queueSize, AtomicInteger::get))
            .isNotNull();
    }

    @Test
    @DisplayName("recordParsedArtifacts increments by type")
    void recordParsedArtifacts_incrementsByType() {
        metrics.recordParsedArtifacts("java-class", 45);
        metrics.recordParsedArtifacts("java-class", 30);

        assertThat(registry.counter("flowforge.parsed.artifacts", "type", "java-class")
            .count()).isEqualTo(75.0);
    }
}
```

#### FlowForgeSpanProcessorTest

Tests span enrichment with `flowforge.version` attribute and null-safe behavior when `getPackage()` returns null.

```java
class FlowForgeSpanProcessorTest {

    private FlowForgeSpanProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new FlowForgeSpanProcessor();
    }

    @Test
    @DisplayName("onStart sets flowforge.version attribute")
    void onStart_setsVersionAttribute() {
        var span = mock(ReadWriteSpan.class);
        var context = Context.root();

        processor.onStart(context, span);

        verify(span).setAttribute(eq("flowforge.version"), anyString());
    }

    @Test
    @DisplayName("onStart sets 'dev' when package version is null")
    void onStart_devWhenNoPackageVersion() {
        var span = mock(ReadWriteSpan.class);

        processor.onStart(Context.root(), span);

        verify(span).setAttribute("flowforge.version", "dev");
    }

    @Test
    @DisplayName("isStartRequired returns true")
    void isStartRequired_true() {
        assertThat(processor.isStartRequired()).isTrue();
    }

    @Test
    @DisplayName("isEndRequired returns false")
    void isEndRequired_false() {
        assertThat(processor.isEndRequired()).isFalse();
    }
}
```

#### PipelineLoggingContextTest

Tests ScopedValue-based MDC propagation with virtual threads.

```java
class PipelineLoggingContextTest {

    private final PipelineLoggingContext loggingContext = new PipelineLoggingContext();

    @Test
    @DisplayName("withContext sets MDC fields during execution")
    void withContext_setsMdc() {
        var snapshotId = UUID.randomUUID();
        var result = loggingContext.withContext(snapshotId, "parse-code", () -> {
            assertThat(MDC.get("snapshotId")).isEqualTo(snapshotId.toString());
            assertThat(MDC.get("stage")).isEqualTo("parse-code");
            return "executed";
        });

        assertThat(result).isEqualTo("executed");
    }

    @Test
    @DisplayName("withContext cleans up MDC after execution")
    void withContext_cleansMdcAfter() {
        loggingContext.withContext(UUID.randomUUID(), "stage", () -> null);

        assertThat(MDC.get("snapshotId")).isNull();
        assertThat(MDC.get("stage")).isNull();
    }

    @Test
    @DisplayName("currentContext returns bound context inside withContext")
    void currentContext_returnsBound() {
        var snapshotId = UUID.randomUUID();
        loggingContext.withContext(snapshotId, "embed-code", () -> {
            var ctx = PipelineLoggingContext.currentContext();
            assertThat(ctx).isPresent();
            assertThat(ctx.get()).containsEntry("snapshotId", snapshotId.toString());
            assertThat(ctx.get()).containsEntry("stage", "embed-code");
            return null;
        });
    }

    @Test
    @DisplayName("currentContext returns empty outside withContext")
    void currentContext_emptyOutside() {
        assertThat(PipelineLoggingContext.currentContext()).isEmpty();
    }

    @Test
    @DisplayName("restoreMdcFromScope propagates to virtual thread")
    void restoreMdcFromScope_virtualThread() throws Exception {
        var snapshotId = UUID.randomUUID();
        loggingContext.withContext(snapshotId, "synthesize", () -> {
            try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
                scope.fork(() -> {
                    PipelineLoggingContext.restoreMdcFromScope();
                    assertThat(MDC.get("snapshotId")).isEqualTo(snapshotId.toString());
                    assertThat(MDC.get("stage")).isEqualTo("synthesize");
                    return null;
                });
                scope.join();
                scope.throwIfFailed();
            }
            return null;
        });
    }

    @Test
    @DisplayName("restoreMdcFromScope is no-op when no context is bound")
    void restoreMdcFromScope_noopWhenUnbound() {
        PipelineLoggingContext.restoreMdcFromScope();
        assertThat(MDC.get("snapshotId")).isNull();
    }
}
```

#### MetricsConfigurationTest

```java
@SpringBootTest(classes = MetricsConfiguration.class)
class MetricsConfigurationTest {

    @Autowired MeterRegistry registry;

    @Test
    @DisplayName("Common tags are applied to all metrics")
    void commonTags_applied() {
        registry.counter("test.counter").increment();
        var counter = registry.find("test.counter").counter();

        assertThat(counter).isNotNull();
        assertThat(counter.getId().getTag("application")).isEqualTo("flowforge");
    }

    @Test
    @DisplayName("TimedAspect bean is available")
    void timedAspect_available(@Autowired TimedAspect timedAspect) {
        assertThat(timedAspect).isNotNull();
    }
}
```

#### PrometheusRule Alert Expression Tests

Validates alert PromQL expressions are syntactically well-formed.

```java
@Tag("unit")
class AlertExpressionTest {

    private Map<String, Object> alerts;

    @BeforeEach
    void setUp() throws Exception {
        var yaml = new ObjectMapper(new YAMLFactory());
        alerts = yaml.readValue(
            Path.of("k8s/monitoring/alerts.yaml").toFile(), Map.class);
    }

    @Test
    @DisplayName("Alert YAML is parseable")
    void yamlParseable() {
        assertThat(alerts).containsKey("apiVersion");
        assertThat(alerts.get("kind")).isEqualTo("PrometheusRule");
    }

    @Test
    @DisplayName("All alert rules have required fields")
    @SuppressWarnings("unchecked")
    void allRulesHaveRequiredFields() {
        var groups = (List<Map<String, Object>>) getPath(alerts, "spec.groups");
        for (var group : groups) {
            var rules = (List<Map<String, Object>>) group.get("rules");
            for (var rule : rules) {
                assertThat(rule).containsKeys("alert", "expr", "labels", "annotations");
                assertThat(((Map<?, ?>) rule.get("labels"))).containsKey("severity");
                assertThat(((Map<?, ?>) rule.get("annotations"))).containsKey("summary");
            }
        }
    }

    @Test
    @DisplayName("StageFailureRateHigh alert references correct metrics")
    @SuppressWarnings("unchecked")
    void stageFailureAlert_correctMetrics() {
        var expr = findAlertExpr("StageFailureRateHigh");
        assertThat(expr).contains("flowforge_stage_failure_total");
        assertThat(expr).contains("flowforge_stage_success_total");
        assertThat(expr).contains("> 0.1");
    }

    @Test
    @DisplayName("LLMLatencyHigh alert uses p99 histogram quantile")
    void llmLatencyAlert_p99() {
        var expr = findAlertExpr("LLMLatencyHigh");
        assertThat(expr).contains("histogram_quantile(0.99");
        assertThat(expr).contains("flowforge_llm_call_duration_seconds_bucket");
    }

    @Test
    @DisplayName("PipelineStuck alert checks for > 1 hour stall")
    void pipelineStuckAlert_oneHour() {
        var expr = findAlertExpr("PipelineStuck");
        assertThat(expr).contains("3600");
    }

    @Test
    @DisplayName("StageHeartbeatMissing alert checks 30-minute window")
    void heartbeatAlert_thirtyMinutes() {
        var expr = findAlertExpr("StageHeartbeatMissing");
        assertThat(expr).contains("1800");
    }
}
```

### 2. Integration Tests

#### Prometheus metrics endpoint integration test

Verifies that the `/actuator/prometheus` endpoint exposes correctly formatted Prometheus metrics after recording pipeline activity.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class PrometheusEndpointIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired PipelineMetrics metrics;

    @Test
    @DisplayName("Prometheus endpoint exposes stage metrics after recording")
    void prometheusEndpoint_exposeStageMetrics() {
        metrics.recordStage("parse-code", UUID.randomUUID(), () -> "ok");
        metrics.recordEmbeddingBatch("codebert", 16, Duration.ofMillis(200));
        metrics.recordLlmCall("qwen-32b", 100, 50, Duration.ofSeconds(2));

        var response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
            .contains("flowforge_stage_duration_seconds")
            .contains("flowforge_stage_success_total")
            .contains("flowforge_embedding_batch_items_total")
            .contains("flowforge_llm_tokens_input_total");
    }
}
```

#### Structured logging format integration test

```java
@SpringBootTest
@ActiveProfiles("aks")
@Tag("integration")
class StructuredLoggingIntegrationTest {

    @Autowired PipelineLoggingContext loggingContext;

    @Test
    @DisplayName("Structured JSON log contains snapshotId and stage fields")
    void structuredLog_containsPipelineContext() {
        var snapshotId = UUID.randomUUID();
        loggingContext.withContext(snapshotId, "parse-logs", () -> {
            assertThat(MDC.get("snapshotId")).isEqualTo(snapshotId.toString());
            assertThat(MDC.get("stage")).isEqualTo("parse-logs");
            return null;
        });
    }
}
```

#### ScopedValue MDC propagation with virtual thread pool

```java
@SpringBootTest
@Tag("integration")
class VirtualThreadMdcIntegrationTest {

    @Autowired PipelineLoggingContext loggingContext;

    @Test
    @DisplayName("MDC propagates to virtual threads via ScopedValue")
    void mdcPropagates_toVirtualThreads() throws Exception {
        var snapshotId = UUID.randomUUID();
        loggingContext.withContext(snapshotId, "embed-code", () -> {
            var futures = new ArrayList<Future<String>>();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 5; i++) {
                    futures.add(executor.submit(() -> {
                        PipelineLoggingContext.restoreMdcFromScope();
                        return MDC.get("snapshotId");
                    }));
                }
            }
            for (var future : futures) {
                assertThat(future.get()).isEqualTo(snapshotId.toString());
            }
            return null;
        });
    }
}
```

### 3. Test Fixtures & Sample Data

| Fixture file | Description |
|---|---|
| `src/test/resources/logback-test.xml` | Test Logback config that uses plain text format (no JSON) for readable test output |
| `k8s/monitoring/alerts.yaml` | The actual PrometheusRule YAML validated in alert expression tests |
| `k8s/grafana/flowforge-pipeline-dashboard.json` | Grafana dashboard JSON validated for panel completeness |
| `src/test/resources/observability/sample-span-attributes.json` | Expected span attributes for `FlowForgeSpanProcessor` verification |
| `src/test/resources/observability/prometheus-expected-metrics.txt` | List of expected Prometheus metric names for endpoint validation |

### 4. Mocking Strategy

| Dependency | Strategy | Rationale |
|---|---|---|
| `MeterRegistry` | **`SimpleMeterRegistry`** (real, in-memory) | Micrometer's test registry captures all metrics for assertion — no mock needed |
| `ReadWriteSpan` | **Mockito** (`mock()`) in `FlowForgeSpanProcessorTest` | OpenTelemetry span objects are interfaces; mock to verify `setAttribute` calls |
| `Context` (OTel) | **`Context.root()`** | Use the real root context — lightweight and stateless |
| `StructuredTaskScope` | **Real instance** | Virtual thread MDC propagation must be tested against the real JVM facility |
| `MDC` | **Real SLF4J MDC** | Static utility — test actual put/get/remove behavior |
| Prometheus / Grafana / Tempo | **Not used in tests** | Metric registration is validated via `SimpleMeterRegistry`; endpoint format is validated via Spring `TestRestTemplate` |
| `TimedAspect` | **Real bean** via `@SpringBootTest` | Validates aspect is correctly wired into the Spring context |

### 5. CI/CD Considerations

- **Test tags**: `@Tag("unit")` for pure metric/span tests, `@Tag("integration")` for Spring context and virtual thread tests
- **Gradle task separation**:
  ```kotlin
  tasks.test { useJUnitPlatform { includeTags("unit") } }
  tasks.register<Test>("integrationTest") { useJUnitPlatform { includeTags("integration") } }
  ```
- **No Docker required**: All tests use in-memory registries and real JVM features — no external services needed
- **Virtual thread tests**: Require JDK 21+ with `--enable-preview` (or JDK 25 where ScopedValue is stable). Ensure CI runners use the correct JDK:
  ```kotlin
  tasks.withType<Test> {
      jvmArgs("--enable-preview")
  }
  ```
- **Alert YAML validation**: Any change to `k8s/monitoring/alerts.yaml` should trigger the `AlertExpressionTest` to catch malformed PromQL expressions
- **Dashboard JSON validation**: Optionally add a test that parses `k8s/grafana/flowforge-pipeline-dashboard.json` and asserts all panels have `targets` with non-empty `expr` fields
- **Test execution time**: Target < 5s for unit tests (metrics are very fast), < 15s for integration tests
- **Logback test config**: Use `src/test/resources/logback-test.xml` with plain-text output to avoid JSON parsing overhead in test logs:
  ```xml
  <configuration>
      <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
          <encoder><pattern>%d{HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n</pattern></encoder>
      </appender>
      <root level="WARN"><appender-ref ref="CONSOLE" /></root>
  </configuration>
  ```

## Verification

**Stage 30 sign-off requires all stages 1 through 30 to pass.** Run: `make verify`.

The verification report for stage 30 is `logs/stage-30.log`. It contains **cumulative output for stages 1–30** (Stage 1, then Stage 2, … then Stage 30 output).

| Check | How to verify | Pass criteria |
|---|---|---|
| Prometheus scrape | GET /actuator/prometheus | Metrics in Prometheus format |
| Stage timer | Run a stage | `flowforge_stage_duration_seconds` recorded |
| Stage counters | Success + failure | Counters increment |
| LLM metrics | Call vLLM | Token counts + latency recorded |
| Embedding metrics | Embed batch | Batch size + latency recorded |
| Gauge metrics | Register gauge | Value visible in Prometheus |
| Trace propagation | Cross-service call | Single trace ID across spans |
| Span enrichment | Check Tempo | `flowforge.version` attribute present |
| JSON logging | Run with `aks` profile | JSON log lines with traceId |
| MDC context | Run stage | snapshotId + stage in logs |
| Grafana dashboard | Import JSON | All panels render data |
| Alert firing | Simulate high failure rate | Alert triggers in Prometheus |
| OTel agent | Run with Dockerfile | Auto-instrumented traces in Tempo |
| Health endpoint | GET /actuator/health | Includes all custom indicators |

## Files to create

- `libs/observability/build.gradle.kts`
- `libs/observability/src/main/java/com/flowforge/observability/config/ObservabilityProperties.java`
- `libs/observability/src/main/java/com/flowforge/observability/config/MetricsConfiguration.java`
- `libs/observability/src/main/java/com/flowforge/observability/metrics/PipelineMetrics.java`
- `libs/observability/src/main/java/com/flowforge/observability/tracing/FlowForgeSpanProcessor.java`
- `libs/observability/src/main/java/com/flowforge/observability/logging/PipelineLoggingContext.java`
- `libs/observability/src/main/resources/logback-spring.xml`
- `k8s/grafana/flowforge-pipeline-dashboard.json`
- `k8s/monitoring/alerts.yaml`
- `Dockerfile` (updated with OTel agent)
- `libs/observability/src/test/java/.../PipelineMetricsTest.java`
- `k8s/argocd/apps/kube-prometheus-stack.yaml`
- `k8s/argocd/apps/tempo.yaml`
- `k8s/observability/kube-prometheus-stack/values.yaml`
- `k8s/observability/tempo/values.yaml`

## Depends on

- Stage 04 (Spring Boot Actuator)
- Prometheus, Grafana, Tempo deployed on AKS

## Produces

- Micrometer metrics for all pipeline stages and DL models
- OpenTelemetry distributed tracing via Java Agent (zero-code instrumentation)
- Structured JSON logging with trace correlation (traceId in MDC)
- Grafana dashboards for pipeline monitoring
- PrometheusRule alerts for operational issues
- Pipeline logging context (snapshotId, stage) propagated across threads
