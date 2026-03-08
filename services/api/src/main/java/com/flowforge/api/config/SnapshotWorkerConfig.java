package com.flowforge.api.config;

import com.flowforge.api.service.JobContextHolder;
import com.flowforge.common.service.MetadataService;
import com.flowforge.ingest.github.GitHubSnapshotWorker;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "flowforge.dispatch.mode", havingValue = "in-process")
public class SnapshotWorkerConfig {

    @Bean("snapshotWorker")
    public Runnable snapshotWorker(GitHubSnapshotWorker githubSnapshotWorker, MetadataService metadataService) {
        return () -> {
            UUID jobId = JobContextHolder.getJobId();
            Map<String, Object> params = JobContextHolder.getParams();
            try {
                if (jobId == null) {
                    return;
                }
                String jobType = metadataService.getJob(jobId)
                    .map(j -> j.getJobType())
                    .orElse("SNAPSHOT");
                if ("SNAPSHOT_REFRESH".equals(jobType)) {
                    githubSnapshotWorker.executeRefresh(jobId);
                } else {
                    String repoUrl = (String) params.getOrDefault("repoUrl", "");
                    String branch = (String) params.getOrDefault("branch", "master");
                    githubSnapshotWorker.executeBaseline(jobId, repoUrl, branch);
                }
            } catch (Exception e) {
                throw new RuntimeException("Snapshot worker failed", e);
            } finally {
                JobContextHolder.clear();
            }
        };
    }
}
