package com.flowforge.patterns.extract;

import com.flowforge.logparser.model.ParsedLogEvent;
import com.flowforge.topology.service.TopologyEnrichmentService.TopologyGraph;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Extracts call sequences from trace-correlated and temporal log events for sequential pattern mining.
 */
@Component
public class CallSequenceExtractor {

    /**
     * A single item in a sequence — represents an event at a service.
     */
    public record SequenceItem(
        String serviceName,
        String eventType,   // ENDPOINT_CALL, KAFKA_PRODUCE, LOG_ERROR, etc.
        String detail       // endpoint path, topic name, template ID
    ) {
        /** Encode as SPMF-compatible integer ID using a thread-safe counter. */
        public int encode(Map<SequenceItem, Integer> itemMap, AtomicInteger counter) {
            return itemMap.computeIfAbsent(this, k -> counter.incrementAndGet());
        }
    }

    /**
     * Extract call sequences from trace-correlated log events.
     */
    public List<List<SequenceItem>> extractFromTraces(
            List<ParsedLogEvent> events,
            TopologyGraph topology) {

        var traceGroups = events.stream()
            .filter(e -> e.traceId().isPresent())
            .collect(Collectors.groupingBy(e -> e.traceId().orElseThrow()));

        return traceGroups.values().stream()
            .filter(group -> group.size() >= 2)
            .map(group -> group.stream()
                .sorted(Comparator.comparing(ParsedLogEvent::timestamp))
                .map(e -> new SequenceItem(
                    e.serviceName(),
                    classifyEventType(e),
                    extractDetail(e)
                ))
                .toList())
            .toList();
    }

    /**
     * Extract sequences from temporal co-occurrence (no trace context).
     */
    public List<List<SequenceItem>> extractFromTemporalWindows(
            List<ParsedLogEvent> events,
            Duration windowSize) {

        if (events.size() < 2) {
            return List.of();
        }

        var sorted = events.stream()
            .sorted(Comparator.comparing(ParsedLogEvent::timestamp))
            .toList();

        var sequences = new ArrayList<List<SequenceItem>>();
        var firstTs = sorted.get(0).timestamp();
        var lastTs = sorted.get(sorted.size() - 1).timestamp();

        var windowStart = firstTs;
        while (windowStart.isBefore(lastTs) || windowStart.equals(lastTs)) {
            var windowEnd = windowStart.plus(windowSize);
            var windowEvents = filterWindow(sorted, windowStart, windowEnd);

            if (windowEvents.size() >= 2) {
                sequences.add(windowEvents.stream()
                    .map(e -> new SequenceItem(
                        e.serviceName(),
                        classifyEventType(e),
                        extractDetail(e)))
                    .toList());
            }
            windowStart = windowEnd;
        }

        return sequences;
    }

    private static List<ParsedLogEvent> filterWindow(
            List<ParsedLogEvent> sorted,
            Instant windowStart,
            Instant windowEnd) {
        return sorted.stream()
            .filter(e -> !e.timestamp().isBefore(windowStart) && e.timestamp().isBefore(windowEnd))
            .toList();
    }

    static String classifyEventType(ParsedLogEvent e) {
        if (e.severity() == ParsedLogEvent.LogSeverity.ERROR
                || e.severity() == ParsedLogEvent.LogSeverity.FATAL) {
            return "LOG_ERROR";
        }
        switch (e.source()) {
            case ISTIO_ACCESS -> { return "ENDPOINT_CALL"; }
            case ISTIO_ENVOY -> { return "ENDPOINT_CALL"; }
            default -> {
                var msg = (e.rawMessage() != null ? e.rawMessage() : "").toLowerCase();
                if (msg.contains("kafka") && msg.contains("produce")) return "KAFKA_PRODUCE";
                if (msg.contains("kafka") && msg.contains("consume")) return "KAFKA_CONSUME";
                return "LOG";
            }
        }
    }

    static String extractDetail(ParsedLogEvent e) {
        if (e.templateId() != null && !e.templateId().isBlank()) {
            return e.templateId();
        }
        if (e.template() != null && !e.template().isBlank()) {
            return e.template().length() > 200 ? e.template().substring(0, 200) : e.template();
        }
        return "";
    }
}
