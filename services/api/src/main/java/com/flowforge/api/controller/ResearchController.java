package com.flowforge.api.controller;

import com.flowforge.api.dto.JobResponse;
import com.flowforge.api.dto.ResearchRunRequest;
import com.flowforge.api.dto.ResearchRunResponse;
import com.flowforge.api.service.JobDispatcher;
import com.flowforge.common.entity.ResearchRunEntity;
import com.flowforge.common.service.MetadataService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/research")
public class ResearchController {

    private final MetadataService metadataService;
    private final JobDispatcher jobDispatcher;

    public ResearchController(MetadataService metadataService, JobDispatcher jobDispatcher) {
        this.metadataService = metadataService;
        this.jobDispatcher = jobDispatcher;
    }

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse startResearchRun(@Valid @RequestBody ResearchRunRequest request) {
        UUID runId = metadataService.createResearchRun(request.snapshotId(), request.blobBatchId());
        jobDispatcher.dispatch("RESEARCH", runId, Map.of(
            "snapshotId", request.snapshotId().toString(),
            "blobBatchId", request.blobBatchId() != null ? request.blobBatchId().toString() : ""
        ));
        return new JobResponse(runId, "PENDING", "Research run created");
    }

    @GetMapping("/latest")
    public ResponseEntity<ResearchRunResponse> getLatestResearch() {
        return metadataService.getLatestResearchRun()
            .map(this::toResponse)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{runId}")
    public ResponseEntity<ResearchRunResponse> getResearchRun(@PathVariable UUID runId) {
        return metadataService.getResearchRun(runId)
            .map(this::toResponse)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    private ResearchRunResponse toResponse(ResearchRunEntity entity) {
        return new ResearchRunResponse(
            entity.getRunId(), entity.getSnapshotId(), entity.getBlobBatchId(),
            entity.getStatus().name(), entity.getCreatedAt(), entity.getCompletedAt(),
            entity.getQualityMetrics(), entity.getOutputPath()
        );
    }
}
