package com.flowforge.anomaly.training;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowforge.anomaly.feature.LogFeatureEngineer.LogFeatureVector;
import com.flowforge.anomaly.model.AnomalyDetectorModel;
import com.flowforge.mlflow.service.ExperimentTracker;
import com.flowforge.mlflow.service.ExperimentTracker.TrainingContext;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrackedAnomalyTrainerTest {

    @Mock
    AnomalyDetectorModel baseModel;
    @Mock
    ExperimentTracker tracker;
    @InjectMocks
    TrackedAnomalyTrainer trackedTrainer;

    @Test
    @DisplayName("trainTracked passes snapshot ID in run name and params")
    void trainTracked_logsCorrectParams() throws Exception {
        when(baseModel.getNumTrees()).thenReturn(100);
        when(baseModel.getSubsampleSize()).thenReturn(256);

        when(tracker.trackRun(eq("anomaly-snap-1"), anyMap(), any()))
            .thenAnswer(inv -> {
                ExperimentTracker.TrainingFunction<?> fn = inv.getArgument(2);
                TrainingContext ctx = mock(TrainingContext.class);
                return fn.train(ctx);
            });

        List<LogFeatureVector> features = List.of(
            new LogFeatureVector(
                "svc",
                java.time.Instant.now(),
                java.time.Instant.now().plusSeconds(60),
                0.1, 0.2, 0.3, 10.0, 0.4, 0.5, 0.6, 0.7,
                100)
        );

        trackedTrainer.trainTracked("snap-1", features);

        verify(tracker).trackRun(eq("anomaly-snap-1"),
            anyMap(),
            any());
    }
}

