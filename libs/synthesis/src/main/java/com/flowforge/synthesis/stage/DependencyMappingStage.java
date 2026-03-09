package com.flowforge.synthesis.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.synthesis.executor.SynthesisStageExecutor;
import com.flowforge.synthesis.model.DependencyMappingOutput;
import com.flowforge.synthesis.model.SynthesisPartialResult;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Stage 4: Map runtime and build dependencies, identify conflicts and shared libraries.
 */
@Component
public class DependencyMappingStage {

    private final SynthesisStageExecutor executor;
    private final MinioStorageClient minio;
    private final ObjectMapper objectMapper;

    public DependencyMappingStage(SynthesisStageExecutor executor,
                                 MinioStorageClient minio,
                                 ObjectMapper objectMapper) {
        this.executor = executor;
        this.minio = minio;
        this.objectMapper = objectMapper;
    }

    public DependencyMappingOutput mapDependencies(
            FlowCandidate candidate,
            SynthesisPartialResult stages1to3) {
        var output = executor.executeStage("stage4", candidate,
            DependencyMappingOutput.class,
            Map.of(
                "priorStageOutput", serializeToString(stages1to3),
                "buildEvidence", formatBuildEvidence(candidate)));

        minio.putJson("evidence",
            "synthesis/stage4/%s/%s.json".formatted(
                candidate.snapshotId(), candidate.candidateId()),
            output);

        return output;
    }

    private String formatBuildEvidence(FlowCandidate candidate) {
        return candidate.evidence().codeSnippets().stream()
            .collect(Collectors.joining("\n---\n"));
    }

    private String serializeToString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize for prompt", e);
        }
    }
}
