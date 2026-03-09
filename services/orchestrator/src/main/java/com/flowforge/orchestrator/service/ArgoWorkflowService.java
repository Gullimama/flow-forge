package com.flowforge.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.flowforge.orchestrator.config.ArgoProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ArgoWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(ArgoWorkflowService.class);

    private final RestClient restClient;
    private final ArgoProperties props;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ArgoWorkflowService(
        ArgoProperties props,
        RestClient.Builder builder,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry
    ) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.restClient = builder
            .baseUrl(props.serverUrl())
            .defaultHeader("Content-Type", "application/json")
            .build();
    }

    /**
     * Submit a new pipeline workflow.
     */
    public WorkflowStatus submitPipeline(PipelineRequest request) {
        Map<String, Object> workflow = loadWorkflowTemplate("flowforge-pipeline.yaml");
        setParameters(workflow, Map.of(
            "snapshot-id", request.snapshotId().toString(),
            "repo-urls", safeWriteJson(request.repoUrls()),
            "log-time-range", request.logTimeRange(),
            "run-gnn", String.valueOf(request.runGnn())
        ));

        WorkflowResponse response = restClient.post()
            .uri("/api/v1/workflows/{ns}", props.namespace())
            .body(Map.of("workflow", workflow))
            .retrieve()
            .body(WorkflowResponse.class);

        meterRegistry.counter("flowforge.argo.workflow.submitted").increment();
        log.info("Submitted workflow: {}", response != null && response.metadata() != null
            ? response.metadata().name() : "<unknown>");

        return new WorkflowStatus(
            response.metadata().name(),
            response.status().phase(),
            response.metadata().creationTimestamp()
        );
    }

    /**
     * Get workflow status by name.
     */
    public WorkflowStatus getStatus(String workflowName) {
        WorkflowResponse response = restClient.get()
            .uri("/api/v1/workflows/{ns}/{name}", props.namespace(), workflowName)
            .retrieve()
            .body(WorkflowResponse.class);

        return new WorkflowStatus(
            response.metadata().name(),
            response.status().phase(),
            response.metadata().creationTimestamp()
        );
    }

    /**
     * Get node-level status for all DAG tasks.
     */
    public List<TaskStatus> getTaskStatuses(String workflowName) {
        WorkflowResponse response = restClient.get()
            .uri("/api/v1/workflows/{ns}/{name}", props.namespace(), workflowName)
            .retrieve()
            .body(WorkflowResponse.class);

        return response.status().nodes().values().stream()
            .filter(n -> "Pod".equals(n.type()))
            .map(n -> new TaskStatus(
                n.displayName(),
                n.phase(),
                n.startedAt(),
                n.finishedAt(),
                n.message()
            ))
            .toList();
    }

    /**
     * Wait for workflow completion with polling.
     */
    public WorkflowStatus waitForCompletion(String workflowName, Duration timeout) throws TimeoutException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            WorkflowStatus status = getStatus(workflowName);
            if (isTerminal(status.phase())) {
                meterRegistry.counter("flowforge.argo.workflow.completed",
                    "phase", status.phase()).increment();
                return status;
            }
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for workflow", e);
            }
        }
        throw new TimeoutException("Workflow did not complete within " + timeout);
    }

    private boolean isTerminal(String phase) {
        return "Succeeded".equals(phase) || "Failed".equals(phase) || "Error".equals(phase);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadWorkflowTemplate(String fileName) {
        String resourcePath = "argo/" + fileName;
        try (InputStream is = Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Workflow template not found on classpath: " + resourcePath);
            }
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            return yamlMapper.readValue(is, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load workflow template " + fileName, e);
        }
    }

    @SuppressWarnings("unchecked")
    private void setParameters(Map<String, Object> workflow, Map<String, String> params) {
        Object specObj = workflow.get("spec");
        if (!(specObj instanceof Map<?, ?> spec)) {
            return;
        }
        Object argsObj = spec.get("arguments");
        if (!(argsObj instanceof Map<?, ?> arguments)) {
            return;
        }
        Object paramsObj = arguments.get("parameters");
        if (!(paramsObj instanceof List<?> list)) {
            return;
        }
        for (Object pObj : list) {
            if (!(pObj instanceof Map<?, ?> pMap)) continue;
            Map<String, Object> param = (Map<String, Object>) pMap;
            String name = Objects.toString(param.get("name"), null);
            if (name != null && params.containsKey(name)) {
                param.put("value", params.get(name));
            }
        }
    }

    private String safeWriteJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize value to JSON", e);
        }
    }
}

// DTOs for Argo Workflow responses
record WorkflowResponse(
    WorkflowMetadata metadata,
    WorkflowStatusBody status
) {}

record WorkflowMetadata(
    String name,
    String creationTimestamp
) {}

record WorkflowStatusBody(
    String phase,
    Map<String, WorkflowNodeStatus> nodes
) {}

record WorkflowNodeStatus(
    String type,
    String displayName,
    String phase,
    String startedAt,
    String finishedAt,
    String message
) {}

