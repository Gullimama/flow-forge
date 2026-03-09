package com.flowforge.synthesis.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.synthesis.executor.SynthesisStageExecutor;
import com.flowforge.synthesis.model.DependencyMappingOutput;
import com.flowforge.synthesis.model.MigrationPlanOutput;
import com.flowforge.synthesis.model.SynthesisPartialResult;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Stage 5: Generate migration strategy, phases, effort, and rollback plan.
 */
@Component
public class MigrationPlanStage {

    private final SynthesisStageExecutor executor;
    private final MinioStorageClient minio;
    private final ObjectMapper objectMapper;

    public MigrationPlanStage(SynthesisStageExecutor executor,
                              MinioStorageClient minio,
                              ObjectMapper objectMapper) {
        this.executor = executor;
        this.minio = minio;
        this.objectMapper = objectMapper;
    }

    public MigrationPlanOutput planMigration(
            FlowCandidate candidate,
            SynthesisPartialResult stages1to3,
            DependencyMappingOutput dependencyMapping) {
        var output = executor.executeStage("stage5", candidate,
            MigrationPlanOutput.class,
            Map.of(
                "riskAssessment", serializeToString(stages1to3.riskAssessment()),
                "dependencyMapping", serializeToString(dependencyMapping)));

        minio.putJson("evidence",
            "synthesis/stage5/%s/%s.json".formatted(
                candidate.snapshotId(), candidate.candidateId()),
            output);

        return output;
    }

    private String serializeToString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize for prompt", e);
        }
    }
}
