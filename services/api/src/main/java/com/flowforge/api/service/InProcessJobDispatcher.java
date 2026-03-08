package com.flowforge.api.service;

import com.flowforge.common.entity.JobStatusEnum;
import com.flowforge.common.service.MetadataService;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "flowforge.dispatch.mode", havingValue = "in-process")
public class InProcessJobDispatcher implements JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InProcessJobDispatcher.class);

    private final ApplicationContext applicationContext;
    private final MetadataService metadataService;

    public InProcessJobDispatcher(ApplicationContext applicationContext, MetadataService metadataService) {
        this.applicationContext = applicationContext;
        this.metadataService = metadataService;
    }

    @Override
    public void dispatch(String jobType, UUID jobId, Map<String, Object> params) {
        Thread.startVirtualThread(() -> {
            try {
                metadataService.updateJobStatus(jobId, JobStatusEnum.RUNNING, 0.0f);
                if ("SNAPSHOT".equals(jobType) || "SNAPSHOT_REFRESH".equals(jobType) || "LOG_INGEST".equals(jobType)) {
                    JobContextHolder.set(jobId, params);
                }
                try {
                    switch (jobType) {
                        case "SNAPSHOT", "SNAPSHOT_REFRESH" -> applicationContext.getBean("snapshotWorker", Runnable.class).run();
                        case "LOG_INGEST" -> applicationContext.getBean("blobIngestionWorker", Runnable.class).run();
                        case "RESEARCH" -> applicationContext.getBean("researchPipeline", Runnable.class).run();
                        default -> throw new IllegalArgumentException("Unknown job type: " + jobType);
                    }
                    metadataService.updateJobStatus(jobId, JobStatusEnum.COMPLETED, 100.0f);
                } finally {
                    if ("SNAPSHOT".equals(jobType) || "SNAPSHOT_REFRESH".equals(jobType) || "LOG_INGEST".equals(jobType)) {
                        JobContextHolder.clear();
                    }
                }
            } catch (Exception e) {
                log.error("Job {} failed: {}", jobId, e.getMessage(), e);
                metadataService.updateJobStatus(jobId, JobStatusEnum.FAILED, -1.0f);
            }
        });
    }
}
