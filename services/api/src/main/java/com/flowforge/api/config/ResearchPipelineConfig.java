package com.flowforge.api.config;

import com.flowforge.common.entity.JobStatusEnum;
import com.flowforge.common.entity.ResearchRunEntity;
import com.flowforge.common.service.MetadataService;
import com.flowforge.flow.builder.FlowCandidateBuilder;
import com.flowforge.publisher.model.PublishResult;
import com.flowforge.publisher.service.OutputPublisher;
import com.flowforge.synthesis.model.SynthesisFullResult;
import com.flowforge.synthesis.pipeline.SynthesisPipeline;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "flowforge.dispatch.mode", havingValue = "in-process")
public class ResearchPipelineConfig {

    private static final Logger log = LoggerFactory.getLogger(ResearchPipelineConfig.class);

    /**
     * Main research pipeline executed by InProcessJobDispatcher for RESEARCH jobs.
     * Flow:
     *  - Resolve ResearchRun by runId
     *  - Build flow candidates for its snapshot
     *  - Run 6-stage synthesis per candidate
     *  - Publish final document to MinIO
     *  - Mark ResearchRun and Job as COMPLETED/FAILED
     */
    @Bean("researchPipeline")
    public Runnable researchPipeline(MetadataService metadataService,
                                     FlowCandidateBuilder flowCandidateBuilder,
                                     SynthesisPipeline synthesisPipeline,
                                     OutputPublisher outputPublisher) {
        return () -> {
            UUID runId = com.flowforge.api.service.JobContextHolder.getJobId();
            if (runId == null) {
                log.warn("researchPipeline invoked without jobId in context; skipping");
                return;
            }
            try {
                ResearchRunEntity run = metadataService.getResearchRun(runId)
                    .orElseThrow(() -> new IllegalStateException("No ResearchRun for id " + runId));
                UUID snapshotId = run.getSnapshotId();
                if (snapshotId == null) {
                    throw new IllegalStateException("ResearchRun " + runId + " has no snapshotId");
                }

                // Build flow candidates from graph + retrieval evidence
                List<com.flowforge.flow.model.FlowCandidate> candidates =
                    flowCandidateBuilder.buildCandidates(snapshotId);
                log.info("Research pipeline {}: built {} flow candidates for snapshot {}",
                    runId, candidates.size(), snapshotId);

                // Run full synthesis pipeline (stages 1–6)
                List<SynthesisFullResult> synthesisResults =
                    synthesisPipeline.synthesize(snapshotId, candidates);
                log.info("Research pipeline {}: synthesis produced {} results",
                    runId, synthesisResults.size());

                // Publish final research document
                PublishResult publishResult =
                    outputPublisher.publish(snapshotId, synthesisResults);
                log.info("Research pipeline {}: published document {}, {} chars, {} flows ({} risks)",
                    runId, publishResult.outputKey(), publishResult.markdownLength(),
                    publishResult.flowCount(), publishResult.riskCount());

                // Mark run and underlying job as COMPLETED
                run.setStatus(com.flowforge.common.entity.ResearchRunStatus.COMPLETED);
                run.setCompletedAt(java.time.Instant.now());
                metadataService.updateJobStatus(run.getJobId(), JobStatusEnum.COMPLETED, 100.0f);
            } catch (Exception e) {
                log.error("Research pipeline {} failed: {}", runId, e.getMessage(), e);
                metadataService.updateJobStatus(runId, JobStatusEnum.FAILED, -1.0f);
                // Best-effort to mark ResearchRun failed if present
                metadataService.getResearchRun(runId).ifPresent(r -> {
                    r.setStatus(com.flowforge.common.entity.ResearchRunStatus.FAILED);
                    r.setCompletedAt(java.time.Instant.now());
                });
            } finally {
                com.flowforge.api.service.JobContextHolder.clear();
            }
        };
    }
}

