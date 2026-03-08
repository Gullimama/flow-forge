package com.flowforge.anomaly.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.flowforge.anomaly.feature.LogFeatureEngineer.LogFeatureVector;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnomalyDetectorModelTest {

    private AnomalyDetectorModel model;

    @BeforeEach
    void setUp() {
        model = new AnomalyDetectorModel(200, 256);
    }

    @Test
    void score_beforeTraining_throwsIllegalStateException() {
        var vector = normalFeatureVector("svc");
        assertThatThrownBy(() -> model.score(vector))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not trained");
    }

    @Test
    void trainAndScore_normalVector_scoresLow() {
        var trainingData = generateNormalFeatureVectors("svc", 200);
        model.train(trainingData);

        var normalVector = normalFeatureVector("svc");
        assertThat(model.score(normalVector)).isLessThan(0.7);
    }

    @Test
    void trainAndScore_anomalousVector_scoresHigh() {
        var trainingData = generateNormalFeatureVectors("svc", 200);
        model.train(trainingData);

        var anomalous = anomalousFeatureVector("svc");
        assertThat(model.score(anomalous)).isGreaterThan(0.5);
    }

    @Test
    void isAnomalous_respectsThreshold() {
        var trainingData = generateNormalFeatureVectors("svc", 200);
        model.train(trainingData);

        var anomalous = anomalousFeatureVector("svc");
        assertThat(model.isAnomalous(anomalous, 0.3)).isTrue();
        assertThat(model.isAnomalous(normalFeatureVector("svc"), 0.99)).isFalse();
    }

    @Test
    void serializeAndDeserialize_producesConsistentScores() throws Exception {
        var trainingData = generateNormalFeatureVectors("svc", 200);
        model.train(trainingData);

        var testVector = anomalousFeatureVector("svc");
        double originalScore = model.score(testVector);

        byte[] serialized = model.serializeModel();
        var restoredModel = new AnomalyDetectorModel(200, 256);
        restoredModel.loadModel(serialized);

        assertThat(restoredModel.score(testVector)).isCloseTo(originalScore, within(0.01));
    }

    private static LogFeatureVector normalFeatureVector(String svc) {
        var start = Instant.now();
        var end = start.plusSeconds(300);
        return new LogFeatureVector(svc, start, end,
            0.02, 0.1, 5.0, 0.0, 0.01, 0.05, 0.8, 0.02, 100);
    }

    private static LogFeatureVector anomalousFeatureVector(String svc) {
        var start = Instant.now();
        var end = start.plusSeconds(300);
        return new LogFeatureVector(svc, start, end,
            0.95, 0.9, 50.0, 0.0, 0.5, 0.8, 0.1, 0.9, 50);
    }

    private static List<LogFeatureVector> generateNormalFeatureVectors(String svc, int count) {
        var list = new ArrayList<LogFeatureVector>();
        var start = Instant.now();
        for (int i = 0; i < count; i++) {
            var s = start.plusSeconds(i * 300L);
            var e = s.plusSeconds(300);
            list.add(new LogFeatureVector(svc, s, e,
                0.01 + (i % 5) * 0.01, 0.08 + (i % 10) * 0.01, 4.0 + (i % 3), 0.0,
                0.005, 0.03 + (i % 5) * 0.01, 0.7 + (i % 3) * 0.05, 0.01 + (i % 2) * 0.01, 80 + i));
        }
        return list;
    }
}
