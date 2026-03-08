package com.flowforge.logparser.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Structured log event after parsing and Drain template extraction.
 */
public record ParsedLogEvent(
    UUID eventId,
    UUID snapshotId,
    String serviceName,
    Instant timestamp,
    LogSeverity severity,
    String templateId,
    String template,
    String rawMessage,
    List<String> parameters,
    Optional<String> traceId,
    Optional<String> spanId,
    Optional<String> threadName,
    Optional<String> loggerName,
    Optional<String> exceptionClass,
    Optional<String> exceptionMessage,
    LogSource source
) {
    public enum LogSeverity { TRACE, DEBUG, INFO, WARN, ERROR, FATAL }

    public enum LogSource { APP, ISTIO_ACCESS, ISTIO_ENVOY }
}
