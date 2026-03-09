package com.flowforge.synthesis.pipeline;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.synthesis.model.SynthesisFullResult;
import com.flowforge.synthesis.service.SynthesisStages1To3Service;
import com.flowforge.synthesis.service.SynthesisStages4To6Service;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Full 6-stage synthesis pipeline: runs stages 1–3 then 4–6 per candidate,
 * stores complete results and checkpoints partial failures.
 */
@Service
public class SynthesisPipeline {

    private static final Logger log = LoggerFactory.getLogger(SynthesisPipeline.class);

    private final SynthesisStages1To3Service stages1to3;
    private final SynthesisStages4To6Service stages4to6;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    public SynthesisPipeline(SynthesisStages1To3Service stages1to3,
                             SynthesisStages4To6Service stages4to6,
                             MinioStorageClient minio,
                             MeterRegistry meterRegistry) {
        this.stages1to3 = stages1to3;
        this.stages4to6 = stages4to6;
        this.minio = minio;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Run the full 6-stage synthesis pipeline for all flow candidates.
     */
    public List<SynthesisFullResult> synthesize(UUID snapshotId, List<FlowCandidate> candidates) {
        log.info("Starting synthesis pipeline for {} candidates", candidates.size());

        var results = new ArrayList<SynthesisFullResult>();

        for (var candidate : candidates) {
            try {
                var partial = stages1to3.runStages1To3(candidate);
                var full = stages4to6.runStages4To6(candidate, partial);
                results.add(full);
                meterRegistry.counter("flowforge.synthesis.flows.completed").increment();
            } catch (Exception e) {
                log.error("Synthesis failed for flow {}: {}",
                    candidate.flowName(), e.getMessage(), e);
                meterRegistry.counter("flowforge.synthesis.flows.failed").increment();
                minio.putJson("evidence",
                    "synthesis/partial/%s/%s.json".formatted(snapshotId, candidate.candidateId()),
                    Map.of(
                        "flowName", candidate.flowName(),
                        "error", e.getMessage(),
                        "failedAt", Instant.now().toString()));
            }
        }

        minio.putJson("evidence", "synthesis/complete/" + snapshotId + ".json", results);
        return results;
    }
}
