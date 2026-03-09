package com.flowforge.mlflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flowforge.mlflow")
public record MlflowProperties(
    String trackingUri,
    String experimentName,
    String artifactLocation,
    Duration timeout,
    int maxRetries
) {
    public MlflowProperties {
        if (trackingUri == null) {
            trackingUri = "http://mlflow.flowforge-obs.svc.cluster.local:5000";
        }
        if (experimentName == null) {
            experimentName = "flowforge-models";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(30);
        }
        if (maxRetries <= 0) {
            maxRetries = 3;
        }
    }
}

