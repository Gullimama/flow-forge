package com.flowforge.gnn.training;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowforge.gnn.data.GraphData;
import com.flowforge.gnn.inference.GnnInferenceService;
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
class TrackedGnnTrainerTest {

    @Mock
    GnnInferenceService inference;
    @Mock
    ExperimentTracker tracker;
    @Mock
    GraphData graphData;
    @InjectMocks
    TrackedGnnTrainer trackedGnnTrainer;

    @Test
    @DisplayName("runTracked logs basic params and delegates to tracker")
    void runTracked_logsParamsAndMetrics() throws Exception {
        when(graphData.numNodes()).thenReturn(10);
        when(graphData.numEdges()).thenReturn(20);
        when(inference.predictLinks(any(GraphData.class), anyDouble()))
            .thenReturn(List.of(mock(GnnInferenceService.LinkPrediction.class)));
        when(inference.classifyNodes(any(GraphData.class)))
            .thenReturn(List.of(mock(GnnInferenceService.NodeClassification.class)));

        when(tracker.trackRun(eq("gnn-training"), anyMap(), any()))
            .thenAnswer(inv -> {
                ExperimentTracker.TrainingFunction<?> fn = inv.getArgument(2);
                TrainingContext ctx = mock(TrainingContext.class);
                return fn.train(ctx);
            });

        trackedGnnTrainer.runTracked(graphData, 0.7);

        verify(tracker).trackRun(eq("gnn-training"), anyMap(), any());
    }
}

