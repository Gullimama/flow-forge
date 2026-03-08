package com.flowforge.anomaly.episode;

import com.flowforge.anomaly.feature.LogFeatureEngineer.LogFeatureVector;
import com.flowforge.anomaly.model.AnomalyDetectorModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AnomalyEpisodeBuilder {

    private static final Duration MERGE_GAP = Duration.ofMinutes(10);

    public record AnomalyEpisode(
        UUID episodeId,
        UUID snapshotId,
        String serviceName,
        java.time.Instant startTime,
        java.time.Instant endTime,
        AnomalySeverity severity,
        double peakScore,
        List<LogFeatureVector> windows,
        List<String> topAnomalousTemplates,
        String summary
    ) {}

    public enum AnomalySeverity { LOW, MEDIUM, HIGH, CRITICAL }

    /**
     * Group consecutive anomalous windows into episodes.
     */
    public List<AnomalyEpisode> buildEpisodes(UUID snapshotId, String serviceName,
                                              List<LogFeatureVector> vectors,
                                              AnomalyDetectorModel model,
                                              double threshold) {
        var anomalousWindows = vectors.stream()
            .filter(v -> model.isAnomalous(v, threshold))
            .sorted(Comparator.comparing(LogFeatureVector::windowStart))
            .toList();

        if (anomalousWindows.isEmpty()) {
            return List.of();
        }

        var episodes = new ArrayList<AnomalyEpisode>();
        var current = new ArrayList<LogFeatureVector>();
        current.add(anomalousWindows.get(0));

        for (int i = 1; i < anomalousWindows.size(); i++) {
            var prev = anomalousWindows.get(i - 1);
            var curr = anomalousWindows.get(i);

            if (Duration.between(prev.windowEnd(), curr.windowStart()).compareTo(MERGE_GAP) <= 0) {
                current.add(curr);
            } else {
                episodes.add(buildEpisode(snapshotId, serviceName, current, model));
                current = new ArrayList<>();
                current.add(curr);
            }
        }
        episodes.add(buildEpisode(snapshotId, serviceName, current, model));

        return episodes;
    }

    private AnomalyEpisode buildEpisode(UUID snapshotId, String serviceName,
                                       List<LogFeatureVector> windows,
                                       AnomalyDetectorModel model) {
        double peakScore = windows.stream()
            .mapToDouble(model::score)
            .max().orElse(0);

        AnomalySeverity severity = classifySeverity(peakScore);

        return new AnomalyEpisode(
            UUID.randomUUID(), snapshotId, serviceName,
            windows.get(0).windowStart(),
            windows.get(windows.size() - 1).windowEnd(),
            severity, peakScore, List.copyOf(windows),
            extractTopTemplates(windows),
            "Anomaly episode: %d windows, peak score %.3f".formatted(windows.size(), peakScore)
        );
    }

    private AnomalySeverity classifySeverity(double score) {
        if (score < 0.6) return AnomalySeverity.LOW;
        if (score < 0.8) return AnomalySeverity.MEDIUM;
        if (score < 0.9) return AnomalySeverity.HIGH;
        return AnomalySeverity.CRITICAL;
    }

    private List<String> extractTopTemplates(List<LogFeatureVector> windows) {
        // LogFeatureVector does not carry template IDs; could be enriched later from raw events.
        return List.of();
    }
}
