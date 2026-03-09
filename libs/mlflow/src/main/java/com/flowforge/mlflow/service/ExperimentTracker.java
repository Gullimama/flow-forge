package com.flowforge.mlflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.mlflow.client.MlflowClient;
import com.flowforge.mlflow.client.MlflowClient.MlflowParam;
import com.flowforge.mlflow.config.MlflowProperties;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExperimentTracker {

    private static final Logger log = LoggerFactory.getLogger(ExperimentTracker.class);

    private final MlflowClient mlflow;
    private final MlflowProperties props;
    private final ObjectMapper objectMapper;

    public ExperimentTracker(MlflowClient mlflow, MlflowProperties props, ObjectMapper objectMapper) {
        this.mlflow = mlflow;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * Track a model training run with parameters, metrics, and artifacts.
     */
    public <T> T trackRun(String runName, Map<String, String> params, TrainingFunction<T> trainingFn) {
        var experimentId = mlflow.getOrCreateExperiment(props.experimentName());
        var tags = Map.of(
            "mlflow.source.name", "flowforge",
            "mlflow.runName", runName,
            "java.version", System.getProperty("java.version")
        );

        var run = mlflow.createRun(experimentId, runName, tags);
        var runId = run.runId();

        try {
            var mlflowParams = params.entrySet().stream()
                .map(e -> new MlflowParam(e.getKey(), e.getValue()))
                .toList();
            mlflow.logBatch(runId, List.of(), mlflowParams);

            var context = new TrainingContext(runId, this);
            var result = trainingFn.train(context);

            mlflow.endRun(runId, "FINISHED");
            log.info("MLflow run {} completed successfully", runId);
            return result;
        } catch (Exception e) {
            mlflow.endRun(runId, "FAILED");
            log.error("MLflow run {} failed", runId, e);
            throw new RuntimeException("Training run failed", e);
        }
    }

    /**
     * Context passed to training functions for logging metrics/artifacts.
     */
    public record TrainingContext(String runId, ExperimentTracker tracker) {

        public void logMetric(String key, double value) {
            tracker.mlflow.logMetric(runId, key, value, 0);
        }

        public void logMetric(String key, double value, long step) {
            tracker.mlflow.logMetric(runId, key, value, step);
        }

        public void logArtifact(String path, Object obj) {
            try {
                var json = tracker.objectMapper.writeValueAsBytes(obj);
                tracker.mlflow.logArtifact(runId, path, json);
            } catch (Exception e) {
                LoggerFactory.getLogger(TrainingContext.class)
                    .warn("Failed to log artifact {}", path, e);
            }
        }

        public void logModel(String path, byte[] modelBytes) {
            tracker.mlflow.logArtifact(runId, path, modelBytes);
        }
    }

    @FunctionalInterface
    public interface TrainingFunction<T> {
        T train(TrainingContext context) throws Exception;
    }
}

