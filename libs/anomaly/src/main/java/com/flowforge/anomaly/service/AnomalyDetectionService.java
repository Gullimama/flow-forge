package com.flowforge.anomaly.service;

import com.flowforge.anomaly.episode.AnomalyEpisodeBuilder;
import com.flowforge.anomaly.feature.LogFeatureEngineer;
import com.flowforge.anomaly.model.AnomalyDetectorModel;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.client.OpenSearchClientWrapper;
import com.flowforge.logparser.model.ParsedLogEvent;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AnomalyDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionService.class);
    private static final double DEFAULT_THRESHOLD = 0.65;
    private static final int SEARCH_SIZE = 10_000;

    private final LogFeatureEngineer featureEngineer;
    private final AnomalyDetectorModel modelTemplate;
    private final AnomalyEpisodeBuilder episodeBuilder;
    private final OpenSearchClientWrapper openSearch;
    private final MinioStorageClient minio;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public AnomalyDetectionService(
        LogFeatureEngineer featureEngineer,
        AnomalyDetectorModel modelTemplate,
        AnomalyEpisodeBuilder episodeBuilder,
        OpenSearchClientWrapper openSearch,
        MinioStorageClient minio,
        io.micrometer.core.instrument.MeterRegistry meterRegistry
    ) {
        this.featureEngineer = featureEngineer;
        this.modelTemplate = modelTemplate;
        this.episodeBuilder = episodeBuilder;
        this.openSearch = openSearch;
        this.minio = minio;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Run anomaly detection on logs for all services in a snapshot.
     */
    public AnomalyDetectionResult detectAnomalies(UUID snapshotId, List<String> serviceNames) {
        var allEpisodes = new ArrayList<AnomalyEpisodeBuilder.AnomalyEpisode>();

        for (String service : serviceNames) {
            var events = fetchLogEvents(snapshotId, service);
            if (events.isEmpty()) {
                continue;
            }

            var features = featureEngineer.extractFeatures(service, events);
            if (features.size() < 2) {
                continue;
            }

            var serviceModel = new AnomalyDetectorModel(
                modelTemplate.getNumTrees(), modelTemplate.getSubsampleSize());
            serviceModel.train(features);

            var episodes = episodeBuilder.buildEpisodes(
                snapshotId, service, features, serviceModel, DEFAULT_THRESHOLD);
            allEpisodes.addAll(episodes);

            try {
                byte[] modelBytes = serviceModel.serializeModel();
                minio.putObject("model-artifacts", "anomaly/" + snapshotId + "/" + service + ".smile",
                    modelBytes, "application/octet-stream");
            } catch (Exception e) {
                log.warn("Failed to store anomaly model for {}: {}", service, e.getMessage());
            }
        }

        if (!allEpisodes.isEmpty()) {
            try {
                var docs = allEpisodes.stream().map(this::episodeToDocument).toList();
                openSearch.bulkIndex("anomaly-episodes", docs);
            } catch (IOException e) {
                log.warn("Failed to index anomaly episodes: {}", e.getMessage());
            }
        }

        minio.putJson("evidence", "anomaly-report/" + snapshotId + ".json",
            Map.of(
                "snapshotId", snapshotId.toString(),
                "totalEpisodes", allEpisodes.size(),
                "episodesBySeverity", groupBySeverity(allEpisodes)
            ));

        meterRegistry.counter("flowforge.anomaly.episodes.detected").increment(allEpisodes.size());

        return new AnomalyDetectionResult(allEpisodes.size(), groupBySeverity(allEpisodes));
    }

    private List<ParsedLogEvent> fetchLogEvents(UUID snapshotId, String serviceName) {
        try {
            Map<String, Object> query = Map.of(
                "query", Map.of(
                    "bool", Map.of(
                        "filter", List.of(
                            Map.of("term", Map.of("batch_id", snapshotId.toString())),
                            Map.of("term", Map.of("service_name", serviceName))
                        )
                    )
                ),
                "sort", List.of(Map.of("timestamp", Map.of("order", "asc")))
            );
            var result = openSearch.search("runtime-events", query, SEARCH_SIZE);
            return result.hits().stream()
                .map(hit -> hitToParsedLogEvent(hit, snapshotId))
                .toList();
        } catch (IOException e) {
            log.warn("Failed to fetch log events for {}: {}", serviceName, e.getMessage());
            return List.of();
        }
    }

    private ParsedLogEvent hitToParsedLogEvent(OpenSearchClientWrapper.SearchHit hit, UUID snapshotId) {
        Map<String, Object> m = hit.getSourceAsMap();
        String batchId = getString(m, "batch_id", null);
        UUID batchUuid = batchId != null && !batchId.isBlank()
            ? UUID.fromString(batchId) : snapshotId;
        return new ParsedLogEvent(
            UUID.randomUUID(),
            batchUuid,
            getString(m, "service_name", ""),
            Instant.parse(getString(m, "timestamp", Instant.now().toString())),
            ParsedLogEvent.LogSeverity.valueOf(getString(m, "severity", "INFO")),
            getString(m, "template_id", ""),
            "",
            getString(m, "message", ""),
            List.of(),
            optional(getString(m, "trace_id", "")),
            optional(getString(m, "span_id", "")),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            ParsedLogEvent.LogSource.APP
        );
    }

    private static String getString(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? v.toString() : def;
    }

    private static java.util.Optional<String> optional(String s) {
        return (s != null && !s.isBlank()) ? java.util.Optional.of(s) : java.util.Optional.empty();
    }

    private Map<String, Object> episodeToDocument(AnomalyEpisodeBuilder.AnomalyEpisode ep) {
        return Map.<String, Object>of(
            "episode_id", ep.episodeId().toString(),
            "snapshot_id", ep.snapshotId().toString(),
            "service_name", ep.serviceName(),
            "start_time", ep.startTime().toString(),
            "end_time", ep.endTime().toString(),
            "severity", ep.severity().name(),
            "score", ep.peakScore(),
            "summary", ep.summary(),
            "indexed_at", Instant.now().toString()
        );
    }

    private Map<AnomalyEpisodeBuilder.AnomalySeverity, Long> groupBySeverity(
        List<AnomalyEpisodeBuilder.AnomalyEpisode> episodes) {
        var map = new EnumMap<AnomalyEpisodeBuilder.AnomalySeverity, Long>(AnomalyEpisodeBuilder.AnomalySeverity.class);
        for (var s : AnomalyEpisodeBuilder.AnomalySeverity.values()) {
            map.put(s, 0L);
        }
        for (var ep : episodes) {
            map.merge(ep.severity(), 1L, Long::sum);
        }
        return map;
    }

    public record AnomalyDetectionResult(
        int totalEpisodes,
        Map<AnomalyEpisodeBuilder.AnomalySeverity, Long> bySeverity
    ) {}
}
