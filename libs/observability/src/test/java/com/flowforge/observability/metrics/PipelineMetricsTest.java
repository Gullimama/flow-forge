package com.flowforge.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PipelineMetricsTest {

    @Test
    @DisplayName("recordStage records success and failure counters")
    void recordStage_recordsCounters() {
        var registry = new SimpleMeterRegistry();
        var metrics = new PipelineMetrics(registry);
        UUID snapshotId = UUID.randomUUID();

        metrics.recordStage("parse-code", snapshotId, () -> "ok");
        try {
            metrics.recordStage("parse-code", snapshotId, () -> {
                throw new RuntimeException("boom");
            });
        } catch (RuntimeException ignored) {}

        Double success = registry.find("flowforge.stage.success")
            .tag("stage", "parse-code")
            .counter()
            .count();
        Double failure = registry.find("flowforge.stage.failure")
            .tag("stage", "parse-code")
            .counter()
            .count();

        assertThat(success).isEqualTo(1.0);
        assertThat(failure).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordLlmCall records duration and token counters")
    void recordLlmCall_recordsMetrics() {
        var registry = new SimpleMeterRegistry();
        var metrics = new PipelineMetrics(registry);

        metrics.recordLlmCall("model", 10, 5, Duration.ofMillis(100));

        assertThat(registry.find("flowforge.llm.call.duration").timer()).isNotNull();
        assertThat(registry.find("flowforge.llm.tokens.input").counter().count()).isEqualTo(10.0);
        assertThat(registry.find("flowforge.llm.tokens.output").counter().count()).isEqualTo(5.0);
    }
}

