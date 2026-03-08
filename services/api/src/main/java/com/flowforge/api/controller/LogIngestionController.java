package com.flowforge.api.controller;

import com.flowforge.api.dto.JobResponse;
import com.flowforge.api.dto.LogIngestRequest;
import com.flowforge.api.dto.ReindexRequest;
import com.flowforge.api.service.JobDispatcher;
import com.flowforge.common.service.MetadataService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/logs")
public class LogIngestionController {

    private final MetadataService metadataService;
    private final JobDispatcher jobDispatcher;

    public LogIngestionController(MetadataService metadataService, JobDispatcher jobDispatcher) {
        this.metadataService = metadataService;
        this.jobDispatcher = jobDispatcher;
    }

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse ingestLogs(@Valid @RequestBody LogIngestRequest request) {
        UUID jobId = metadataService.createJob("LOG_INGEST", Map.of(
            "storageAccount", request.storageAccount() != null ? request.storageAccount() : "",
            "container", request.container() != null ? request.container() : "",
            "prefix", request.prefix() != null ? request.prefix() : "",
            "mode", request.mode().name()
        ));
        jobDispatcher.dispatch("LOG_INGEST", jobId, Map.of("mode", request.mode().name()));
        return new JobResponse(jobId, "PENDING", "Log ingestion job created");
    }

    @PostMapping("/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse reindexLogs(@Valid @RequestBody ReindexRequest request) {
        UUID jobId = metadataService.createJob("LOG_REINDEX", Map.of(
            "blobBatchId", request.blobBatchId() != null ? request.blobBatchId().toString() : ""
        ));
        jobDispatcher.dispatch("LOG_REINDEX", jobId, Map.of());
        return new JobResponse(jobId, "PENDING", "Log reindex job created");
    }
}
