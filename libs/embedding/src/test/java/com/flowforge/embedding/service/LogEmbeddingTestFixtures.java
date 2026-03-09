package com.flowforge.embedding.service;

import com.flowforge.logparser.drain.DrainParser;
import com.flowforge.logparser.model.ParsedLogEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class LogEmbeddingTestFixtures {

    static ParsedLogEvent parsedLogEvent(String serviceName, ParsedLogEvent.LogSeverity severity, String template) {
        return new ParsedLogEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            serviceName,
            Instant.now(),
            severity,
            "tmpl-1",
            template,
            template,
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            ParsedLogEvent.LogSource.APP
        );
    }

    static ParsedLogEvent parsedLogEventWithException(String serviceName, ParsedLogEvent.LogSeverity severity,
            String template, String exceptionClass, String exceptionMessage) {
        return new ParsedLogEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            serviceName,
            Instant.now(),
            severity,
            "tmpl-1",
            template,
            template,
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(exceptionClass),
            Optional.of(exceptionMessage),
            ParsedLogEvent.LogSource.APP
        );
    }

    static DrainParser.LogCluster drainCluster(String clusterId, String templateString, long matchCount) {
        return new DrainParser.LogCluster(
            clusterId,
            List.of(templateString.split(" ")),
            new AtomicLong(matchCount)
        );
    }

    static LogEmbeddingTextBuilder.StoredDrainCluster storedDrainCluster(String clusterId, String template, long matchCount) {
        return new LogEmbeddingTextBuilder.StoredDrainCluster(clusterId, template, matchCount);
    }

    private LogEmbeddingTestFixtures() {}
}
