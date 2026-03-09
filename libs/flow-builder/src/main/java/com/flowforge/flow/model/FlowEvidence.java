package com.flowforge.flow.model;

import com.flowforge.anomaly.episode.AnomalyEpisodeBuilder;
import com.flowforge.patterns.analysis.EnrichedPattern;
import java.util.List;
import java.util.Map;

public record FlowEvidence(
    List<String> codeSnippets,
    List<String> logPatterns,
    List<String> graphPaths,
    List<EnrichedPattern> sequencePatterns,
    List<AnomalyEpisodeBuilder.AnomalyEpisode> relatedAnomalies,
    Map<String, Object> topologyContext
) {
    public static FlowEvidence empty() {
        return new FlowEvidence(
            List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
    }
}
