package com.flowforge.anomaly.episode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.flowforge.anomaly.feature.LogFeatureEngineer.LogFeatureVector;
import com.flowforge.anomaly.model.AnomalyDetectorModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnomalyEpisodeBuilderTest {

    private final AnomalyEpisodeBuilder builder = new AnomalyEpisodeBuilder();

    @Test
    void buildEpisodes_noAnomalousWindows_returnsEmpty() {
        var model = new AnomalyDetectorModel(200, 256);
        var vectors = generateNormalFeatureVectors("svc", 10);
        model.train(vectors);

        var episodes = builder.buildEpisodes(UUID.randomUUID(), "svc", vectors, model, 0.65);

        assertThat(episodes).isEmpty();
    }

    @Test
    void buildEpisodes_threeConsecutiveAnomalousWindows_producesSingleEpisode() {
        var vectors = sequentialFeatureVectors("svc", 10, Duration.ofMinutes(5));
        var stubModel = new StubScoreModel(Map.of(
            vectors.get(2).windowStart(), 0.7,
            vectors.get(3).windowStart(), 0.72,
            vectors.get(4).windowStart(), 0.68
        ));

        var episodes = builder.buildEpisodes(UUID.randomUUID(), "svc", vectors, stubModel, 0.65);

        assertThat(episodes).hasSize(1);
        assertThat(episodes.get(0).windows()).hasSize(3);
    }

    @Test
    void buildEpisodes_fifteenMinuteGap_producesTwoEpisodes() {
        var vectors = sequentialFeatureVectors("svc", 10, Duration.ofMinutes(5));
        var stubModel = trainedModelWithAnomalousIndices(vectors, Set.of(0, 1, 5, 6));

        var episodes = builder.buildEpisodes(UUID.randomUUID(), "svc", vectors, stubModel, 0.65);

        assertThat(episodes).hasSize(2);
    }

    @Test
    void buildEpisodes_tenMinuteGap_mergesIntoSingleEpisode() {
        var vectors = sequentialFeatureVectors("svc", 10, Duration.ofMinutes(5));
        var stubModel = trainedModelWithAnomalousIndices(vectors, Set.of(0, 1, 3, 4));

        var episodes = builder.buildEpisodes(UUID.randomUUID(), "svc", vectors, stubModel, 0.65);

        assertThat(episodes).hasSize(1);
    }

    @Test
    void buildEpisodes_peakScoreReflectsHighestWindowScore() {
        var vectors = sequentialFeatureVectors("svc", 10, Duration.ofMinutes(5));
        var stubModel = new StubScoreModel(Map.of(
            vectors.get(2).windowStart(), 0.7,
            vectors.get(3).windowStart(), 0.85,
            vectors.get(4).windowStart(), 0.8
        ));

        var episodes = builder.buildEpisodes(UUID.randomUUID(), "svc", vectors, stubModel, 0.65);

        assertThat(episodes.get(0).peakScore()).isCloseTo(0.85, within(0.01));
        assertThat(episodes.get(0).severity()).isEqualTo(AnomalyEpisodeBuilder.AnomalySeverity.HIGH);
    }

    private static StubScoreModel trainedModelWithAnomalousIndices(List<LogFeatureVector> vectors, Set<Integer> indices) {
        var map = new java.util.HashMap<Instant, Double>();
        for (int i = 0; i < vectors.size(); i++) {
            map.put(vectors.get(i).windowStart(), indices.contains(i) ? 0.7 : 0.3);
        }
        return new StubScoreModel(map);
    }

    private static List<LogFeatureVector> sequentialFeatureVectors(String svc, int count, Duration windowSize) {
        var list = new java.util.ArrayList<LogFeatureVector>();
        var start = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        for (int i = 0; i < count; i++) {
            var s = start.plus(windowSize.multipliedBy(i));
            var e = s.plus(windowSize);
            list.add(new LogFeatureVector(svc, s, e, 0.02, 0.1, 5.0, 0.0, 0.01, 0.05, 0.8, 0.02, 100));
        }
        return list;
    }

    private static List<LogFeatureVector> generateNormalFeatureVectors(String svc, int count) {
        return sequentialFeatureVectors(svc, count, Duration.ofMinutes(5));
    }

    /** Stub model that returns fixed scores per window start. */
    private static class StubScoreModel extends AnomalyDetectorModel {
        private final Map<Instant, Double> scoreByWindowStart;

        StubScoreModel(Map<Instant, Double> scoreByWindowStart) {
            super(200, 256);
            this.scoreByWindowStart = Map.copyOf(scoreByWindowStart);
        }

        @Override
        public double score(LogFeatureVector vector) {
            return scoreByWindowStart.getOrDefault(vector.windowStart(), 0.3);
        }
    }
}
