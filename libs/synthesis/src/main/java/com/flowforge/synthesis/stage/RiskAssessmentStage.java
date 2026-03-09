package com.flowforge.synthesis.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.synthesis.executor.SynthesisStageExecutor;
import com.flowforge.synthesis.model.CodeExplanationOutput;
import com.flowforge.synthesis.model.FlowAnalysisOutput;
import com.flowforge.synthesis.model.RiskAssessmentOutput;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Stage 3: Assess migration risks for the flow.
 */
@Component
public class RiskAssessmentStage {

    private final SynthesisStageExecutor executor;
    private final MinioStorageClient minio;
    private final ObjectMapper objectMapper;

    public RiskAssessmentStage(SynthesisStageExecutor executor,
                               MinioStorageClient minio,
                               ObjectMapper objectMapper) {
        this.executor = executor;
        this.minio = minio;
        this.objectMapper = objectMapper;
    }

    /**
     * Assess migration risks for the flow.
     */
    public RiskAssessmentOutput assess(FlowCandidate candidate,
                                        FlowAnalysisOutput flowAnalysis,
                                        CodeExplanationOutput codeExplanation) {
        var output = executor.executeStage("stage3", candidate,
            RiskAssessmentOutput.class,
            Map.of(
                "flowAnalysis", serializeToString(flowAnalysis),
                "codeExplanation", serializeToString(codeExplanation)));
        minio.putJson("evidence",
            "synthesis/stage3/%s/%s.json".formatted(
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
