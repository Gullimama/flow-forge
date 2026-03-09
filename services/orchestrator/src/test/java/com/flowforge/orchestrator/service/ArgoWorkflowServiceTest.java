package com.flowforge.orchestrator.service;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.orchestrator.config.ArgoProperties;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

@WireMockTest
class ArgoWorkflowServiceTest {

    private ArgoWorkflowService service;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        var props = new ArgoProperties(
            wm.getHttpBaseUrl(),
            "flowforge",
            "flowforge-workflow",
            "IfNotPresent",
            Duration.ofHours(6),
            null,
            Map.of()
        );
        service = new ArgoWorkflowService(
            props,
            RestClient.builder(),
            new ObjectMapper(),
            meterRegistry
        );
    }

    @Test
    @DisplayName("submitPipeline sends workflow and returns status")
    void submitPipeline_returnsStatus() {
        stubFor(post(urlPathEqualTo("/api/v1/workflows/flowforge"))
            .willReturn(okJson("""
                {"metadata": {"name": "flowforge-pipeline-abc", "creationTimestamp": "2025-01-01T00:00:00Z"},
                 "status": {"phase": "Running"}}
                """)));

        var request = new PipelineRequest(
            UUID.randomUUID(),
            List.of("https://github.com/org/repo"),
            "24h",
            true
        );

        WorkflowStatus status = service.submitPipeline(request);

        assertThat(status.name()).isEqualTo("flowforge-pipeline-abc");
        assertThat(status.phase()).isEqualTo("Running");
    }

    @Test
    @DisplayName("getStatus fetches workflow status by name")
    void getStatus_returnsStatus() {
        stubFor(get(urlPathEqualTo("/api/v1/workflows/flowforge/my-workflow"))
            .willReturn(okJson("""
                {"metadata": {"name": "my-workflow", "creationTimestamp": "2025-01-01T00:00:00Z"},
                 "status": {"phase": "Succeeded"}}
                """)));

        WorkflowStatus status = service.getStatus("my-workflow");

        assertThat(status.name()).isEqualTo("my-workflow");
        assertThat(status.phase()).isEqualTo("Succeeded");
    }

    @Test
    @DisplayName("getTaskStatuses returns pod node statuses")
    void getTaskStatuses_returnsPods() {
        stubFor(get(urlPathEqualTo("/api/v1/workflows/flowforge/my-workflow"))
            .willReturn(okJson("""
                {"metadata": {"name": "my-workflow", "creationTimestamp": "2025-01-01T00:00:00Z"},
                 "status": {"phase": "Succeeded",
                           "nodes": {
                             "node1": {"type": "Pod", "displayName": "task-a", "phase": "Succeeded",
                                       "startedAt": "2025-01-01T00:00:00Z", "finishedAt": "2025-01-01T00:01:00Z",
                                       "message": "ok"},
                             "node2": {"type": "Steps", "displayName": "root", "phase": "Succeeded"}
                           }}}
                """)));

        var tasks = service.getTaskStatuses("my-workflow");

        assertThat(tasks).hasSize(1);
        TaskStatus task = tasks.getFirst();
        assertThat(task.name()).isEqualTo("task-a");
        assertThat(task.phase()).isEqualTo("Succeeded");
    }

    @Test
    @DisplayName("waitForCompletion stops on terminal phase")
    void waitForCompletion_stopsOnTerminal() throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/workflows/flowforge/work"))
            .willReturn(okJson("""
                {"metadata": {"name": "work", "creationTimestamp": "2025-01-01T00:00:00Z"},
                 "status": {"phase": "Succeeded"}}
                """)));

        WorkflowStatus status = service.waitForCompletion("work", Duration.ofSeconds(1));

        assertThat(status.phase()).isEqualTo("Succeeded");
    }
}

