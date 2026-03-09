package com.flowforge.mlflow.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.mlflow.config.MlflowProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class MlflowClient {

    private static final Logger log = LoggerFactory.getLogger(MlflowClient.class);

    private final RestClient restClient;
    private final MlflowProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MlflowClient(MlflowProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder
            .baseUrl(props.trackingUri())
            .defaultHeader("Content-Type", "application/json")
            .build();
    }

    // ── Experiment management ──────────────────────────────

    public String getOrCreateExperiment(String name) {
        try {
            String json = restClient.get()
                .uri("/api/2.0/mlflow/experiments/get-by-name?experiment_name={name}", name)
                .retrieve()
                .body(String.class);
            JsonNode node = objectMapper.readTree(json);
            return node.path("experiment").path("experiment_id").asText();
        } catch (HttpClientErrorException.NotFound e) {
            try {
                Map<String, Object> body = Map.of(
                    "name", name,
                    "artifact_location", props.artifactLocation()
                );
                String json = restClient.post()
                    .uri("/api/2.0/mlflow/experiments/create")
                    .body(body)
                    .retrieve()
                    .body(String.class);
                JsonNode node = objectMapper.readTree(json);
                return node.path("experiment_id").asText();
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to create experiment " + name, ex);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to get experiment " + name, ex);
        }
    }

    // ── Run management ─────────────────────────────────────

    public MlflowRun createRun(String experimentId, String runName, Map<String, String> tags) {
        var tagList = tags.entrySet().stream()
            .map(e -> Map.of("key", e.getKey(), "value", e.getValue()))
            .toList();
        Map<String, Object> body = Map.of(
            "experiment_id", experimentId,
            "run_name", runName,
            "tags", tagList
        );
        String json = restClient.post()
            .uri("/api/2.0/mlflow/runs/create")
            .body(body)
            .retrieve()
            .body(String.class);
        try {
            JsonNode node = objectMapper.readTree(json).path("run").path("info");
            String runId = node.path("run_id").asText();
            String expId = node.path("experiment_id").asText(null);
            String status = node.path("status").asText(null);
            long startTime = node.path("start_time").asLong(0L);
            var info = new RunInfo(runId, expId, status, startTime, null);
            return new MlflowRun(info, null);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse createRun response", e);
        }
    }

    public void endRun(String runId, String status) {
        Map<String, Object> body = Map.of(
            "run_id", runId,
            "status", status,
            "end_time", Instant.now().toEpochMilli()
        );
        restClient.post()
            .uri("/api/2.0/mlflow/runs/update")
            .body(body)
            .retrieve()
            .toBodilessEntity();
    }

    // ── Logging ────────────────────────────────────────────

    public void logParam(String runId, String key, String value) {
        Map<String, Object> body = Map.of(
            "run_id", runId,
            "key", key,
            "value", value
        );
        restClient.post()
            .uri("/api/2.0/mlflow/runs/log-parameter")
            .body(body)
            .retrieve()
            .toBodilessEntity();
    }

    public void logMetric(String runId, String key, double value, long step) {
        Map<String, Object> body = Map.of(
            "run_id", runId,
            "key", key,
            "value", value,
            "timestamp", Instant.now().toEpochMilli(),
            "step", step
        );
        restClient.post()
            .uri("/api/2.0/mlflow/runs/log-metric")
            .body(body)
            .retrieve()
            .toBodilessEntity();
    }

    public void logBatch(String runId, List<MlflowMetric> metrics, List<MlflowParam> params) {
        Map<String, Object> body = Map.of(
            "run_id", runId,
            "metrics", metrics,
            "params", params
        );
        restClient.post()
            .uri("/api/2.0/mlflow/runs/log-batch")
            .body(body)
            .retrieve()
            .toBodilessEntity();
    }

    public void logArtifact(String runId, String artifactPath, byte[] data) {
        restClient.put()
            .uri("/api/2.0/mlflow-artifacts/artifacts/{path}?run_id={runId}", artifactPath, runId)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(data)
            .retrieve()
            .toBodilessEntity();
    }

    // ── DTOs ──────────────────────────────────────────────────

    public record MlflowRun(RunInfo info, RunData data) {
        public String runId() {
            return info != null ? info.runId() : null;
        }
    }

    public record RunInfo(String runId, String experimentId, String status, long startTime, Long endTime) {}

    public record RunData(List<MlflowMetric> metrics, List<MlflowParam> params) {}

    public record MlflowMetric(String key, double value, long timestamp, long step) {}

    public record MlflowParam(String key, String value) {}

    public record ExperimentResponse(Experiment experiment) {}

    public record Experiment(String experimentId, String name) {}

    public record CreateExperimentResponse(String experimentId) {}

    public record RunResponse(MlflowRun run) {}
}

