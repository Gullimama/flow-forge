package com.flowforge.mlflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.mlflow.client.MlflowClient;
import com.flowforge.mlflow.client.MlflowClient.MlflowRun;
import com.flowforge.mlflow.client.MlflowClient.RunData;
import com.flowforge.mlflow.client.MlflowClient.RunInfo;
import com.flowforge.mlflow.config.MlflowProperties;
import com.flowforge.mlflow.service.ExperimentTracker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ExperimentTrackerTest {

    @Mock
    MlflowClient mlflowClient;
    @Mock
    ObjectMapper objectMapper;
    @InjectMocks
    ExperimentTracker tracker;

    private final MlflowProperties props = new MlflowProperties(
        "http://localhost:5000", "test-exp", "s3://art", Duration.ofSeconds(5), 3);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tracker, "props", props);
    }

    @Test
    @DisplayName("trackRun completes run on success")
    void trackRun_successfulTraining() {
        when(mlflowClient.getOrCreateExperiment("test-exp")).thenReturn("1");
        when(mlflowClient.createRun(eq("1"), anyString(), anyMap()))
            .thenReturn(new MlflowRun(
                new RunInfo("run-1", "1", "RUNNING", 0L, null),
                new RunData(List.of(), List.of())));

        var result = tracker.trackRun("test", Map.of("k", "v"), ctx -> {
            ctx.logMetric("acc", 0.9);
            return "done";
        });

        assertThat(result).isEqualTo("done");
        verify(mlflowClient).logBatch(eq("run-1"), eq(List.of()), anyList());
        verify(mlflowClient).endRun("run-1", "FINISHED");
    }

    @Test
    @DisplayName("trackRun marks run FAILED on exception")
    void trackRun_failedTraining() {
        when(mlflowClient.getOrCreateExperiment("test-exp")).thenReturn("1");
        when(mlflowClient.createRun(eq("1"), anyString(), anyMap()))
            .thenReturn(new MlflowRun(
                new RunInfo("run-2", "1", "RUNNING", 0L, null),
                new RunData(List.of(), List.of())));

        assertThatThrownBy(() ->
            tracker.trackRun("test", Map.of(), ctx -> {
                throw new RuntimeException("training error");
            })
        ).isInstanceOf(RuntimeException.class);

        verify(mlflowClient).endRun("run-2", "FAILED");
    }
}

