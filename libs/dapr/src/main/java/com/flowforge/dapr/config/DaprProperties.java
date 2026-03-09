package com.flowforge.dapr.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

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

