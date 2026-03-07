package com.flowforge.common.model;

import java.time.Instant;
import java.util.Map;

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
