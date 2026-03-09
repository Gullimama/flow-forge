package com.flowforge.observability.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
        if (metrics == null) {
            metrics = new MetricsConfig(
                true,
                "/actuator/prometheus",
                Duration.ofSeconds(30),
                Map.of("application", "flowforge", "team", "platform")
            );
        }
        if (tracing == null) {
            tracing = new TracingConfig(
                true,
                "http://tempo.flowforge-obs.svc.cluster.local:4318",
                1.0,
                List.of("tracecontext", "baggage")
            );
        }
        if (logging == null) {
            logging = new LoggingConfig(
                "INFO",
                true,
                List.of("/health", "/prometheus")
            );
        }
    }
}

