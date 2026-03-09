package com.flowforge.embedding.service;

import com.flowforge.logparser.drain.DrainParser;
import com.flowforge.logparser.model.ParsedLogEvent;
import org.springframework.stereotype.Component;

/**
 * Builds embedding-friendly text for log events and templates.
 * E5-large-v2 expects "query:" or "passage:" prefixed text.
 */
@Component
public class LogEmbeddingTextBuilder {

    /**
     * Build embedding-friendly text from a log event (passage for indexing).
     */
    public String buildPassageText(ParsedLogEvent event) {
        var sb = new StringBuilder("passage: ");
        sb.append("[").append(event.serviceName()).append("] ");
        sb.append(event.severity().name()).append(": ");
        sb.append(event.template());
        event.exceptionClass().ifPresent(exc -> {
            sb.append(" | exception: ").append(exc);
            event.exceptionMessage().ifPresent(msg ->
                sb.append(" - ").append(truncate(msg, 200)));
        });
        event.loggerName().ifPresent(logger ->
            sb.append(" | logger: ").append(logger));
        return sb.toString();
    }

    /**
     * Build query text for searching log embeddings.
     */
    public String buildQueryText(String query) {
        return "query: " + query;
    }

    /**
     * Build text from a Drain cluster template for embedding.
     */
    public String buildTemplateText(String serviceName, DrainParser.LogCluster cluster) {
        return "passage: [%s] Log template: %s (frequency: %d)"
            .formatted(serviceName, cluster.templateString(), cluster.matchCount().get());
    }

    /**
     * Build template text from stored cluster info (e.g. from MinIO evidence).
     */
    public String buildTemplateText(String serviceName, StoredDrainCluster cluster) {
        return "passage: [%s] Log template: %s (frequency: %d)"
            .formatted(serviceName, cluster.template(), cluster.matchCount());
    }

    private static String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /** DTO for drain cluster as stored in MinIO (evidence/drain-clusters/{snapshotId}.json). */
    public record StoredDrainCluster(String clusterId, String template, long matchCount) {}
}
