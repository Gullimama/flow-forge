package com.flowforge.logparser.service;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.client.OpenSearchClientWrapper;
import com.flowforge.logparser.drain.DrainParser;
import com.flowforge.logparser.model.ParsedLogEvent;
import com.flowforge.logparser.parser.LogLineParser;
import com.flowforge.logparser.parser.TraceContextExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

@Service
public class LogParsingService {

    private final LogLineParser lineParser;
    private final DrainParser drainParser;
    private final TraceContextExtractor traceExtractor;
    private final OpenSearchClientWrapper openSearch;
    private final MinioStorageClient minio;

    public LogParsingService(
        LogLineParser lineParser,
        DrainParser drainParser,
        TraceContextExtractor traceExtractor,
        OpenSearchClientWrapper openSearch,
        MinioStorageClient minio
    ) {
        this.lineParser = lineParser;
        this.drainParser = drainParser;
        this.traceExtractor = traceExtractor;
        this.openSearch = openSearch;
        this.minio = minio;
    }

    /**
     * Parse all log files under logDir for a snapshot: line parse, Drain cluster, trace extract, index to runtime-events, store clusters in MinIO.
     */
    public LogParseResult parseSnapshotLogs(UUID snapshotId, Path logDir) {
        List<Path> logFiles = findLogFiles(logDir);
        List<Map<String, Object>> events = new ArrayList<>();
        AtomicInteger failedLines = new AtomicInteger(0);

        for (Path file : logFiles) {
            String serviceName = inferServiceName(file);
            List<String> lines = readLines(file);
            for (String line : lines) {
                lineParser.parse(line).ifPresentOrElse(
                    raw -> {
                        DrainParser.LogCluster cluster = drainParser.parse(raw.message());
                        Optional<TraceContextExtractor.TraceContext> trace = traceExtractor.extract(line);
                        ParsedLogEvent event = buildEvent(snapshotId, serviceName, raw, cluster, trace);
                        events.add(eventToDocument(event));
                    },
                    () -> failedLines.incrementAndGet()
                );
            }
        }

        if (!events.isEmpty()) {
            try {
                openSearch.bulkIndex("runtime-events", events);
            } catch (IOException e) {
                throw new RuntimeException("Failed to bulk index runtime-events", e);
            }
        }

        List<Map<String, Object>> clusterReport = drainParser.getClusters().values().stream()
            .map(c -> Map.<String, Object>of(
                "clusterId", c.clusterId(),
                "template", c.templateString(),
                "matchCount", c.matchCount().get()
            ))
            .toList();
        minio.putJson("evidence", "drain-clusters/" + snapshotId + ".json", clusterReport);

        return new LogParseResult(
            logFiles.size(),
            events.size(),
            failedLines.get(),
            drainParser.getClusters().size()
        );
    }

    private ParsedLogEvent buildEvent(
        UUID snapshotId,
        String serviceName,
        LogLineParser.RawLogLine raw,
        DrainParser.LogCluster cluster,
        Optional<TraceContextExtractor.TraceContext> trace
    ) {
        return new ParsedLogEvent(
            UUID.randomUUID(),
            snapshotId,
            serviceName,
            raw.timestamp(),
            raw.severity(),
            cluster.clusterId(),
            cluster.templateString(),
            raw.message(),
            List.of(),
            trace.map(TraceContextExtractor.TraceContext::traceId),
            trace.map(TraceContextExtractor.TraceContext::spanId),
            raw.thread(),
            raw.logger(),
            Optional.empty(),
            Optional.empty(),
            raw.source()
        );
    }

    private Map<String, Object> eventToDocument(ParsedLogEvent event) {
        return Map.<String, Object>ofEntries(
            Map.entry("batch_id", event.snapshotId().toString()),
            Map.entry("service_name", event.serviceName()),
            Map.entry("log_type", event.source().name()),
            Map.entry("timestamp", event.timestamp().toString()),
            Map.entry("template_id", event.templateId()),
            Map.entry("message", event.rawMessage()),
            Map.entry("trace_id", event.traceId().orElse("")),
            Map.entry("span_id", event.spanId().orElse("")),
            Map.entry("severity", event.severity().name()),
            Map.entry("indexed_at", Instant.now().toString())
        );
    }

    private List<Path> findLogFiles(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream
                .filter(p -> p.toString().endsWith(".log"))
                .filter(Files::isRegularFile)
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list log files in " + dir, e);
        }
    }

    private List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            return List.of();
        }
    }

    private String inferServiceName(Path logFile) {
        Path parent = logFile.getParent();
        if (parent == null) return "unknown";
        String name = parent.getFileName().toString();
        String pathStr = logFile.toString().replace('\\', '/');
        String[] parts = pathStr.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].endsWith("-service") || parts[i].endsWith("-api")) {
                return parts[i];
            }
        }
        return name;
    }

    public record LogParseResult(int filesProcessed, int eventsIndexed, int failedLines, int uniqueTemplates) {}
}
