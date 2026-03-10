package com.flowforge.api.controller;

import com.flowforge.api.dto.JobResponse;
import com.flowforge.api.dto.ResearchRunRequest;
import com.flowforge.api.dto.ResearchRunResponse;
import com.flowforge.api.service.JobDispatcher;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.entity.ResearchRunEntity;
import com.flowforge.common.service.MetadataService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    private static final String OUTPUT_BUCKET = "output";
    private static final String RESEARCH_OUTPUT_KEY_TEMPLATE = "system-flows-research/%s/system-flows-research.md";

    private final MetadataService metadataService;
    private final JobDispatcher jobDispatcher;
    private final MinioStorageClient minioStorageClient;

    public ResearchController(MetadataService metadataService,
                              JobDispatcher jobDispatcher,
                              MinioStorageClient minioStorageClient) {
        this.metadataService = metadataService;
        this.jobDispatcher = jobDispatcher;
        this.minioStorageClient = minioStorageClient;
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

    /**
     * Download the research flows markdown document for a snapshot.
     * Returns the published system-flows-research.md from MinIO (output bucket).
     */
    @GetMapping(value = "/output/{snapshotId}", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public ResponseEntity<String> getResearchOutput(@PathVariable UUID snapshotId) {
        String key = RESEARCH_OUTPUT_KEY_TEMPLATE.formatted(snapshotId);
        if (!minioStorageClient.objectExists(OUTPUT_BUCKET, key)) {
            return ResponseEntity.notFound().build();
        }
        String markdown = minioStorageClient.getString(OUTPUT_BUCKET, key);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"system-flows-research.md\"")
            .body(markdown);
    }

    private ResearchRunResponse toResponse(ResearchRunEntity entity) {
        return new ResearchRunResponse(
            entity.getRunId(), entity.getSnapshotId(), entity.getBlobBatchId(),
            entity.getStatus().name(), entity.getCreatedAt(), entity.getCompletedAt(),
            entity.getQualityMetrics(), entity.getOutputPath()
        );
    }
}
