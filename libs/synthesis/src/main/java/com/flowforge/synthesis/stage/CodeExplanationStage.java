package com.flowforge.synthesis.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.synthesis.executor.SynthesisStageExecutor;
import com.flowforge.synthesis.model.CodeExplanationOutput;
import com.flowforge.synthesis.model.FlowAnalysisOutput;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Stage 2: Explain code artifacts in each flow step.
 */
@Component
public class CodeExplanationStage {

    private final SynthesisStageExecutor executor;
    private final MinioStorageClient minio;
    private final ObjectMapper objectMapper;

    public CodeExplanationStage(SynthesisStageExecutor executor,
                                MinioStorageClient minio,
                                ObjectMapper objectMapper) {
        this.executor = executor;
        this.minio = minio;
        this.objectMapper = objectMapper;
    }

    /**
     * Explain code artifacts in each flow step.
     */
    public CodeExplanationOutput explain(FlowCandidate candidate, FlowAnalysisOutput flowAnalysis) {
        var output = executor.executeStage("stage2", candidate,
            CodeExplanationOutput.class,
            Map.of("priorStageOutput", serializeToString(flowAnalysis)));
        minio.putJson("evidence",
            "synthesis/stage2/%s/%s.json".formatted(
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
