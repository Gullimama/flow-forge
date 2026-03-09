package com.flowforge.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class PipelineMetrics {

    private final MeterRegistry registry;

    private final Map<String, Timer> stageTimers = new ConcurrentHashMap<>();
    private final Map<String, Counter> stageSuccessCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> stageFailureCounters = new ConcurrentHashMap<>();

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public <T> T recordStage(String stageName, UUID snapshotId, Supplier<T> execution) {
        Timer timer = stageTimers.computeIfAbsent(stageName,
            name -> Timer.builder("flowforge.stage.duration")
                .tag("stage", name)
                .description("Pipeline stage execution duration")
                .register(registry));

        return timer.record(() -> {
            try {
                T result = execution.get();
                stageSuccessCounters.computeIfAbsent(stageName,
                    name -> Counter.builder("flowforge.stage.success")
                        .tag("stage", name)
                        .register(registry))
                    .increment();
                return result;
            } catch (Exception e) {
                stageFailureCounters.computeIfAbsent(stageName,
                    name -> Counter.builder("flowforge.stage.failure")
                        .tag("stage", name)
                        .register(registry))
                    .increment();
                throw e;
            }
        });
    }

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

    public void recordLlmCall(String modelName, int inputTokens, int outputTokens, Duration latency) {
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

