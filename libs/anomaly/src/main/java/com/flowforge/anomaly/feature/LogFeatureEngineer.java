package com.flowforge.anomaly.feature;

import com.flowforge.logparser.model.ParsedLogEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class LogFeatureEngineer {

    private static final Duration WINDOW_SIZE = Duration.ofMinutes(5);

    public record LogFeatureVector(
        String serviceName,
        Instant windowStart,
        Instant windowEnd,
        double errorRate,
        double uniqueTemplateRatio,
        double eventRate,
        double p99Latency,
        double errorBurstScore,
        double newTemplateRate,
        double traceSpanRatio,
        double exceptionRate,
        int totalEvents
    ) {}

    /**
     * Convert raw log events into windowed feature vectors.
     */
    public List<LogFeatureVector> extractFeatures(String serviceName, List<ParsedLogEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        var sorted = events.stream()
            .sorted(Comparator.comparing(ParsedLogEvent::timestamp))
            .toList();

        var vectors = new ArrayList<LogFeatureVector>();
        var windowStart = sorted.get(0).timestamp().truncatedTo(ChronoUnit.MINUTES);
        var end = sorted.get(sorted.size() - 1).timestamp();

        while (windowStart.isBefore(end)) {
            var windowEnd = windowStart.plus(WINDOW_SIZE);
            var windowEvents = filterWindow(sorted, windowStart, windowEnd);

            if (!windowEvents.isEmpty()) {
                vectors.add(computeFeatures(serviceName, windowStart, windowEnd, windowEvents));
            }
            windowStart = windowEnd;
        }

        return vectors;
    }

    private List<ParsedLogEvent> filterWindow(List<ParsedLogEvent> sorted, Instant windowStart, Instant windowEnd) {
        return sorted.stream()
            .filter(e -> !e.timestamp().isBefore(windowStart) && e.timestamp().isBefore(windowEnd))
            .toList();
    }

    private LogFeatureVector computeFeatures(String serviceName, Instant start, Instant end,
                                            List<ParsedLogEvent> events) {
        int total = events.size();
        long errors = events.stream()
            .filter(e -> e.severity() == ParsedLogEvent.LogSeverity.ERROR
                || e.severity() == ParsedLogEvent.LogSeverity.FATAL)
            .count();
        long uniqueTemplates = events.stream()
            .map(ParsedLogEvent::templateId)
            .distinct()
            .count();
        long withTrace = events.stream()
            .filter(e -> e.traceId().isPresent() && e.traceId().get() != null && !e.traceId().get().isBlank())
            .count();
        long withException = events.stream()
            .filter(e -> e.exceptionClass().isPresent() && e.exceptionClass().get() != null && !e.exceptionClass().get().isBlank())
            .count();

        double seconds = Math.max(Duration.between(start, end).toSeconds(), 1.0);

        return new LogFeatureVector(
            serviceName, start, end,
            total > 0 ? (double) errors / total : 0.0,
            total > 0 ? (double) uniqueTemplates / total : 0.0,
            total / seconds,
            computeP99Latency(events),
            computeErrorBurstScore(events),
            computeNewTemplateRate(events),
            total > 0 ? (double) withTrace / total : 0.0,
            total > 0 ? (double) withException / total : 0.0,
            total
        );
    }

    private double computeP99Latency(List<ParsedLogEvent> events) {
        // Latency not in ParsedLogEvent; from Istio access logs would be parsed separately. Use 0.
        return 0.0;
    }

    private double computeErrorBurstScore(List<ParsedLogEvent> events) {
        if (events.isEmpty()) return 0.0;
        int maxBurst = 0;
        int current = 0;
        for (ParsedLogEvent e : events) {
            if (e.severity() == ParsedLogEvent.LogSeverity.ERROR || e.severity() == ParsedLogEvent.LogSeverity.FATAL) {
                current++;
                maxBurst = Math.max(maxBurst, current);
            } else {
                current = 0;
            }
        }
        return maxBurst / (double) Math.max(events.size(), 1);
    }

    private double computeNewTemplateRate(List<ParsedLogEvent> events) {
        if (events.isEmpty()) return 0.0;
        Map<String, Long> templateCounts = events.stream()
            .collect(Collectors.groupingBy(ParsedLogEvent::templateId, Collectors.counting()));
        long newTemplateEvents = events.stream()
            .filter(e -> templateCounts.getOrDefault(e.templateId(), 0L) < 3)
            .count();
        return (double) newTemplateEvents / events.size();
    }
}
