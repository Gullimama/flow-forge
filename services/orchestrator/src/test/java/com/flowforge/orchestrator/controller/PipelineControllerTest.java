package com.flowforge.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowforge.orchestrator.service.ArgoWorkflowService;
import com.flowforge.orchestrator.service.PipelineRequest;
import com.flowforge.orchestrator.service.TaskStatus;
import com.flowforge.orchestrator.service.WorkflowStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PipelineControllerTest {

    @Mock
    ArgoWorkflowService argoService;

    @InjectMocks
    PipelineController controller;

    @Test
    @DisplayName("submitPipeline delegates to ArgoWorkflowService and returns 202")
    void submitPipeline_delegates() {
        var req = new PipelineRequest(UUID.randomUUID(), List.of(), "24h", true);
        when(argoService.submitPipeline(any(PipelineRequest.class)))
            .thenReturn(new WorkflowStatus("wf", "Running", "ts"));

        var response = controller.submitPipeline(req);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(argoService).submitPipeline(any(PipelineRequest.class));
    }

    @Test
    @DisplayName("getStatus returns workflow status")
    void getStatus_returnsStatus() {
        when(argoService.getStatus("wf"))
            .thenReturn(new WorkflowStatus("wf", "Succeeded", "ts"));

        var status = controller.getStatus("wf");

        assertThat(status.phase()).isEqualTo("Succeeded");
    }

    @Test
    @DisplayName("getTaskStatuses returns task list")
    void getTaskStatuses_returnsTasks() {
        when(argoService.getTaskStatuses("wf"))
            .thenReturn(List.of(new TaskStatus("t1", "Succeeded", "s", "f", "ok")));

        var tasks = controller.getTaskStatuses("wf");

        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().name()).isEqualTo("t1");
    }
}

