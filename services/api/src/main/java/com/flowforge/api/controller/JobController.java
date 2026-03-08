package com.flowforge.api.controller;

import com.flowforge.api.dto.JobStatusResponse;
import com.flowforge.common.entity.JobEntity;
import com.flowforge.common.service.MetadataService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final MetadataService metadataService;

    public JobController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobStatusResponse> getJobStatus(@PathVariable UUID jobId) {
        return metadataService.getJob(jobId)
            .map(this::toResponse)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    private JobStatusResponse toResponse(JobEntity job) {
        return new JobStatusResponse(
            job.getJobId(), job.getJobType(), job.getStatus().name(),
            job.getProgressPct(), job.getCreatedAt(), job.getStartedAt(),
            job.getCompletedAt(), job.getErrorMessage()
        );
    }
}
