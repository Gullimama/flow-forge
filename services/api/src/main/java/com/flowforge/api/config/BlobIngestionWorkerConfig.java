package com.flowforge.api.config;

import com.flowforge.api.service.JobContextHolder;
import com.flowforge.common.entity.JobStatusEnum;
import com.flowforge.common.service.MetadataService;
import com.flowforge.ingest.blob.BlobIngestionConfig;
import com.flowforge.ingest.blob.BlobIngestionWorker;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "flowforge.dispatch.mode", havingValue = "in-process")
public class BlobIngestionWorkerConfig {

    private static final Logger log = LoggerFactory.getLogger(BlobIngestionWorkerConfig.class);

    @Bean("blobIngestionWorker")
    public Runnable blobIngestionWorker(
            Optional<BlobIngestionWorker> workerOpt,
            MetadataService metadataService) {
        return () -> {
            UUID jobId = JobContextHolder.getJobId();
            Map<String, Object> params = JobContextHolder.getParams();
            try {
                if (jobId == null) {
                    return;
                }
                if (workerOpt.isEmpty()) {
                    log.warn("Blob ingestion skipped: Azure not configured");
                    metadataService.updateJobStatus(jobId, JobStatusEnum.FAILED, -1.0f);
                    return;
                }
                String storageAccount = (String) params.getOrDefault("storageAccount", "");
                String container = (String) params.getOrDefault("container", "");
                String prefix = (String) params.getOrDefault("prefix", "");
                String mode = (String) params.getOrDefault("mode", "FULL");
                BlobIngestionConfig config = new BlobIngestionConfig(storageAccount, container, prefix);

                BlobIngestionWorker worker = workerOpt.get();
                if ("INCREMENTAL".equalsIgnoreCase(mode)) {
                    worker.executeIncremental(jobId, config);
                } else {
                    worker.executeFull(jobId, config);
                }
            } catch (Exception e) {
                log.error("Blob ingestion failed for job {}: {}", jobId, e.getMessage(), e);
                if (jobId != null) {
                    metadataService.updateJobStatus(jobId, JobStatusEnum.FAILED, -1.0f);
                }
                throw new RuntimeException("Blob ingestion failed", e);
            }
        };
    }
}
