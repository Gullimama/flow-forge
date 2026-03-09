package com.flowforge.orchestrator.controller;

import com.flowforge.orchestrator.service.ArgoWorkflowService;
import com.flowforge.orchestrator.service.PipelineRequest;
import com.flowforge.orchestrator.service.TaskStatus;
import com.flowforge.orchestrator.service.WorkflowStatus;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pipelines")
public class PipelineController {

    private final ArgoWorkflowService argoService;

    public PipelineController(ArgoWorkflowService argoService) {
        this.argoService = argoService;
    }

    @PostMapping
    public ResponseEntity<WorkflowStatus> submitPipeline(
        @RequestBody PipelineRequest request
    ) {
        WorkflowStatus status = argoService.submitPipeline(request);
        return ResponseEntity.accepted().body(status);
    }

    @GetMapping("/{name}")
    public WorkflowStatus getStatus(@PathVariable String name) {
        return argoService.getStatus(name);
    }

    @GetMapping("/{name}/tasks")
    public List<TaskStatus> getTaskStatuses(@PathVariable String name) {
        return argoService.getTaskStatuses(name);
    }
}

