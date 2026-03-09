package com.flowforge.embedding.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.client.OpenSearchClientWrapper;
import com.flowforge.logparser.model.ParsedLogEvent;
import com.flowforge.vectorstore.service.VectorStoreService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

@Service
public class LogEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(LogEmbeddingService.class);
    private static final int BATCH_SIZE = 128;
    private static final int MAX_EVENTS = 50_000;
    private static final int FETCH_PAGE_SIZE = 10_000;
    private static final int DIMENSIONS = 1024;

    private final VectorStoreService vectorStoreService;
    private final LogEmbeddingTextBuilder textBuilder;
    private final OpenSearchClientWrapper openSearch;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    public LogEmbeddingService(
            VectorStoreService vectorStoreService,
            LogEmbeddingTextBuilder textBuilder,
            OpenSearchClientWrapper openSearch,
            MinioStorageClient minio,
            MeterRegistry meterRegistry) {
        this.vectorStoreService = vectorStoreService;
        this.textBuilder = textBuilder;
        this.openSearch = openSearch;
        this.minio = minio;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Embed log events for a snapshot: unique templates from evidence + stratified sample of events.
     */
    public LogEmbeddingResult embedSnapshot(UUID snapshotId) {
        List<LogEmbeddingTextBuilder.StoredDrainCluster> clusters = fetchDrainClusters(snapshotId);
        log.info("Found {} unique log templates for snapshot {}", clusters.size(), snapshotId);

        List<ParsedLogEvent> allEvents = fetchLogEvents(snapshotId);
        Map<String, String> templateIdToService = allEvents.stream()
            .collect(Collectors.toMap(ParsedLogEvent::templateId, ParsedLogEvent::serviceName, (a, b) -> a));

        List<Document> templateDocs = clusters.stream()
            .map(c -> buildTemplateDocument(snapshotId, templateIdToService.getOrDefault(c.clusterId(), ""), c))
            .toList();

        List<ParsedLogEvent> sampledEvents = fetchAndSampleEvents(allEvents, MAX_EVENTS);
        List<Document> eventDocs = sampledEvents.stream()
            .map(event -> buildEventDocument(snapshotId, event))
            .toList();

        List<Document> allDocs = new ArrayList<>(templateDocs);
        allDocs.addAll(eventDocs);

        for (List<Document> batch : partition(allDocs, BATCH_SIZE)) {
            meterRegistry.timer("flowforge.embedding.log.batch").record(() ->
                vectorStoreService.addLogDocuments(batch)
            );
        }

        LogEmbeddingStats stats = new LogEmbeddingStats(snapshotId, templateDocs.size(),
            eventDocs.size(), DIMENSIONS, "intfloat/e5-large-v2");
        minio.putJson("evidence", "embeddings/log/" + snapshotId + ".json", stats);

        meterRegistry.counter("flowforge.embedding.log.total").increment(allDocs.size());

        return new LogEmbeddingResult(templateDocs.size(), eventDocs.size(), DIMENSIONS);
    }

    private Document buildTemplateDocument(UUID snapshotId, String serviceName,
            LogEmbeddingTextBuilder.StoredDrainCluster cluster) {
        String content = textBuilder.buildTemplateText(serviceName, cluster);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("snapshot_id", snapshotId.toString());
        metadata.put("service_name", serviceName);
        metadata.put("type", "template");
        metadata.put("template_id", cluster.clusterId());
        metadata.put("match_count", cluster.matchCount());
        return new Document(content, metadata);
    }

    private Document buildEventDocument(UUID snapshotId, ParsedLogEvent event) {
        String content = textBuilder.buildPassageText(event);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("snapshot_id", snapshotId.toString());
        metadata.put("service_name", event.serviceName());
        metadata.put("type", "event");
        metadata.put("severity", event.severity().name());
        metadata.put("template_id", event.templateId());
        event.traceId().ifPresent(t -> metadata.put("trace_id", t));
        event.exceptionClass().ifPresent(e -> metadata.put("exception_class", e));
        return new Document(content, metadata);
    }

    /**
     * Stratified sampling: keep all ERROR/FATAL, sample INFO/DEBUG up to maxEvents.
     */
    List<ParsedLogEvent> fetchAndSampleEvents(List<ParsedLogEvent> allEvents, int maxEvents) {
        var errors = allEvents.stream()
            .filter(e -> e.severity() == ParsedLogEvent.LogSeverity.ERROR
                || e.severity() == ParsedLogEvent.LogSeverity.FATAL)
            .toList();

        int remaining = maxEvents - errors.size();
        if (remaining <= 0) {
            return errors.subList(0, Math.min(errors.size(), maxEvents));
        }

        var nonErrors = allEvents.stream()
            .filter(e -> e.severity() != ParsedLogEvent.LogSeverity.ERROR
                && e.severity() != ParsedLogEvent.LogSeverity.FATAL)
            .toList();

        var sampled = new ArrayList<ParsedLogEvent>(errors);
        if (nonErrors.size() <= remaining) {
            sampled.addAll(nonErrors);
        } else {
            var byService = nonErrors.stream().collect(Collectors.groupingBy(ParsedLogEvent::serviceName));
            int perService = remaining / Math.max(byService.size(), 1);
            for (List<ParsedLogEvent> events : byService.values()) {
                sampled.addAll(events.subList(0, Math.min(events.size(), perService)));
            }
        }
        return sampled;
    }

    private List<LogEmbeddingTextBuilder.StoredDrainCluster> fetchDrainClusters(UUID snapshotId) {
        String key = "drain-clusters/" + snapshotId + ".json";
        try {
            List<Map<String, Object>> raw = minio.getJson("evidence", key, new TypeReference<>() {});
            if (raw == null) return List.of();
            return raw.stream()
                .map(m -> new LogEmbeddingTextBuilder.StoredDrainCluster(
                    getString(m, "clusterId", ""),
                    getString(m, "template", ""),
                    getLong(m, "matchCount", 0L)))
                .toList();
        } catch (Exception e) {
            log.warn("Could not load drain clusters for {}: {}", snapshotId, e.getMessage());
            return List.of();
        }
    }

    private List<ParsedLogEvent> fetchLogEvents(UUID snapshotId) {
        List<ParsedLogEvent> all = new ArrayList<>();
        Object[] searchAfter = null;

        try {
            while (true) {
                Map<String, Object> query = new HashMap<>();
                query.put("query", Map.of("term", Map.of("batch_id", snapshotId.toString())));
                query.put("size", FETCH_PAGE_SIZE);
                query.put("sort", List.of(Map.of("timestamp", Map.of("order", "asc"))));
                if (searchAfter != null) {
                    query.put("search_after", Arrays.asList(searchAfter));
                }

                var result = openSearch.searchPaginated("runtime-events", query);
                List<OpenSearchClientWrapper.SearchHitWithSort> hits = result.getHits();
                if (hits.isEmpty()) break;

                for (OpenSearchClientWrapper.SearchHitWithSort hit : hits) {
                    all.add(hitToParsedLogEvent(hit.getSourceAsMap(), snapshotId));
                }
                if (hits.size() < FETCH_PAGE_SIZE) break;
                searchAfter = hits.get(hits.size() - 1).getSortValues();
            }
        } catch (Exception e) {
            log.warn("Failed to fetch log events for {}: {}", snapshotId, e.getMessage());
        }
        return all;
    }

    private static ParsedLogEvent hitToParsedLogEvent(Map<String, Object> m, UUID snapshotId) {
        String batchId = getString(m, "batch_id", null);
        UUID batchUuid = batchId != null && !batchId.isBlank() ? UUID.fromString(batchId) : snapshotId;
        String message = getString(m, "message", "");
        return new ParsedLogEvent(
            UUID.randomUUID(),
            batchUuid,
            getString(m, "service_name", ""),
            Instant.parse(getString(m, "timestamp", Instant.now().toString())),
            ParsedLogEvent.LogSeverity.valueOf(getString(m, "severity", "INFO")),
            getString(m, "template_id", ""),
            message,
            message,
            List.of(),
            optString(getString(m, "trace_id", "")),
            optString(getString(m, "span_id", "")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            ParsedLogEvent.LogSource.APP
        );
    }

    private static String getString(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? v.toString() : def;
    }

    private static long getLong(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Optional<String> optString(String s) {
        return (s != null && !s.isBlank()) ? Optional.of(s) : Optional.empty();
    }

    private static <T> List<List<T>> partition(List<T> list, int batchSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            partitions.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return partitions;
    }
}
