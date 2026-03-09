package com.flowforge.dapr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dapr.client.DaprClient;
import io.dapr.client.domain.HttpExtension;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DaprServiceInvoker {

    private static final Logger log = LoggerFactory.getLogger(DaprServiceInvoker.class);

    private final DaprClient daprClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public DaprServiceInvoker(DaprClient daprClient, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.daprClient = daprClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Invoke a method on another FlowForge service via Dapr service invocation.
     */
    public <T> T invoke(String appId, String method, Object request, Class<T> responseType) {
        return meterRegistry.timer("flowforge.dapr.invoke.latency", "appId", appId, "method", method)
            .record(() -> {
                try {
                    T response = daprClient
                        .invokeMethod(appId, method, request, HttpExtension.POST, responseType)
                        .block(Duration.ofSeconds(30));
                    meterRegistry.counter("flowforge.dapr.invoke.success", "appId", appId).increment();
                    return response;
                } catch (Exception e) {
                    meterRegistry.counter("flowforge.dapr.invoke.error", "appId", appId).increment();
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

class DaprInvocationException extends RuntimeException {
    DaprInvocationException(String appId, String method, Throwable cause) {
        super("Dapr invocation failed for %s/%s".formatted(appId, method), cause);
    }
}

