package com.flowforge.synthesis.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.synthesis.executor.SynthesisStageExecutor;
import com.flowforge.synthesis.model.DependencyMappingOutput;
import com.flowforge.synthesis.model.FinalNarrativeOutput;
import com.flowforge.synthesis.model.MigrationPlanOutput;
import com.flowforge.synthesis.model.SynthesisPartialResult;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Stage 6: Generate final narrative section for the research document.
 */
@Component
public class FinalNarrativeStage {

    private final SynthesisStageExecutor executor;
    private final MinioStorageClient minio;
    private final ObjectMapper objectMapper;

    public FinalNarrativeStage(SynthesisStageExecutor executor,
                               MinioStorageClient minio,
                               ObjectMapper objectMapper) {
        this.executor = executor;
        this.minio = minio;
        this.objectMapper = objectMapper;
    }

    public FinalNarrativeOutput generateNarrative(
            FlowCandidate candidate,
            SynthesisPartialResult stages1to3,
            DependencyMappingOutput dependencyMapping,
            MigrationPlanOutput migrationPlan) {
        var output = executor.executeStage("stage6", candidate,
            FinalNarrativeOutput.class,
            Map.of(
                "flowAnalysis", serializeToString(stages1to3.flowAnalysis()),
                "codeExplanation", serializeToString(stages1to3.codeExplanation()),
                "riskAssessment", serializeToString(stages1to3.riskAssessment()),
                "dependencyMapping", serializeToString(dependencyMapping),
                "migrationPlan", serializeToString(migrationPlan)));

        minio.putJson("evidence",
            "synthesis/stage6/%s/%s.json".formatted(
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
