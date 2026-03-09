package com.flowforge.patterns.analysis;

import com.flowforge.patterns.extract.CallSequenceExtractor.SequenceItem;
import com.flowforge.patterns.mining.SequencePatternMiner.DiscoveredPattern;
import com.flowforge.topology.service.TopologyEnrichmentService.TopologyGraph;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Enriches discovered patterns with domain-specific analysis (services, errors, Kafka, description).
 */
@Component
public class PatternAnalyzer {

    public List<EnrichedPattern> analyzePatterns(
            List<DiscoveredPattern> patterns,
            TopologyGraph topology) {
        return patterns.stream()
            .map(p -> enrichPattern(p, topology))
            .toList();
    }

    private EnrichedPattern enrichPattern(DiscoveredPattern pattern, TopologyGraph topology) {
        var services = pattern.items().stream()
            .map(SequenceItem::serviceName)
            .distinct()
            .toList();

        var hasErrors = pattern.items().stream()
            .anyMatch(i -> "LOG_ERROR".equals(i.eventType()));

        var crossesServiceBoundary = services.size() > 1;

        var involvesKafka = pattern.items().stream()
            .anyMatch(i -> "KAFKA_PRODUCE".equals(i.eventType()) || "KAFKA_CONSUME".equals(i.eventType()));

        return new EnrichedPattern(
            pattern,
            services,
            crossesServiceBoundary,
            hasErrors,
            involvesKafka,
            generatePatternDescription(pattern)
        );
    }

    private String generatePatternDescription(DiscoveredPattern pattern) {
        var sb = new StringBuilder();
        for (int i = 0; i < pattern.items().size(); i++) {
            var item = pattern.items().get(i);
            if (i > 0) sb.append(" → ");
            sb.append(item.serviceName()).append("::").append(item.eventType());
            if (item.detail() != null && !item.detail().isEmpty()) {
                sb.append("(").append(item.detail()).append(")");
            }
        }
        return sb.toString();
    }
}
