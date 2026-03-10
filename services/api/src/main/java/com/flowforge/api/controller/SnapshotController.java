package com.flowforge.api.controller;

import com.flowforge.api.dto.JobResponse;
import com.flowforge.api.dto.SnapshotRequest;
import com.flowforge.api.dto.SnapshotResponse;
import com.flowforge.api.service.JobDispatcher;
import com.flowforge.common.entity.SnapshotEntity;
import com.flowforge.common.service.MetadataService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/snapshots")
public class SnapshotController {

    private final MetadataService metadataService;
    private final JobDispatcher jobDispatcher;

    public SnapshotController(MetadataService metadataService, JobDispatcher jobDispatcher) {
        this.metadataService = metadataService;
        this.jobDispatcher = jobDispatcher;
    }

    @GetMapping("/latest")
    public ResponseEntity<SnapshotResponse> getLatestSnapshot() {
        return metadataService.getLatestSnapshot()
            .map(this::toResponse)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    private SnapshotResponse toResponse(SnapshotEntity e) {
        return new SnapshotResponse(
            e.getSnapshotId(), e.getRepoUrl(), e.getBranch(), e.getCommitSha(),
            e.getStatus() != null ? e.getStatus().name() : null, e.getCreatedAt());
    }

    @PostMapping("/master")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse createBaselineSnapshot(@Valid @RequestBody SnapshotRequest request) {
        UUID jobId = metadataService.createJob("SNAPSHOT", Map.of(
            "repoUrl", request.repoUrl(),
            "githubToken", request.githubToken() != null ? request.githubToken() : ""
        ));
        jobDispatcher.dispatch("SNAPSHOT", jobId, Map.of("repoUrl", request.repoUrl()));
        return new JobResponse(jobId, "PENDING", "Baseline snapshot job created");
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse refreshSnapshot() {
        UUID jobId = metadataService.createJob("SNAPSHOT_REFRESH", Map.of());
        jobDispatcher.dispatch("SNAPSHOT_REFRESH", jobId, Map.of());
        return new JobResponse(jobId, "PENDING", "Refresh snapshot job created");
    }
}
