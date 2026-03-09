package com.flowforge.orchestrator.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

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

    public record RetryPolicy(
        int limit,
        String retryPolicy,
        Duration backoffDuration,
        int backoffFactor,
        Duration backoffMaxDuration
    ) {}

    public record StageResources(
        String cpuRequest,
        String cpuLimit,
        String memoryRequest,
        String memoryLimit,
        String gpuLimit
    ) {}

    public ArgoProperties {
        if (serverUrl == null) {
            serverUrl = "https://argo.flowforge.svc.cluster.local:2746";
        }
        if (namespace == null) {
            namespace = "flowforge";
        }
        if (serviceAccountName == null) {
            serviceAccountName = "flowforge-workflow";
        }
        if (imagePullPolicy == null) {
            imagePullPolicy = "IfNotPresent";
        }
        if (workflowTimeout == null) {
            workflowTimeout = Duration.ofHours(6);
        }
        if (defaultRetry == null) {
            defaultRetry = new RetryPolicy(
                3,
                "Always",
                Duration.ofSeconds(30),
                2,
                Duration.ofMinutes(10)
            );
        }
    }
}

