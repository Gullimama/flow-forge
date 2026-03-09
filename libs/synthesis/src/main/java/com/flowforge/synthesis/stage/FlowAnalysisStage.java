package com.flowforge.synthesis.stage;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.synthesis.executor.SynthesisStageExecutor;
import com.flowforge.synthesis.model.FlowAnalysisOutput;
import org.springframework.stereotype.Component;

/**
 * Stage 1: Analyze flow purpose, interactions, and data flow.
 */
@Component
public class FlowAnalysisStage {

    private final SynthesisStageExecutor executor;
    private final MinioStorageClient minio;

    public FlowAnalysisStage(SynthesisStageExecutor executor, MinioStorageClient minio) {
        this.executor = executor;
        this.minio = minio;
    }

    /**
     * Analyze a flow candidate: purpose, interactions, data flow.
     */
    public FlowAnalysisOutput analyze(FlowCandidate candidate) {
        var output = executor.executeStage("stage1", candidate, FlowAnalysisOutput.class);
        minio.putJson("evidence",
            "synthesis/stage1/%s/%s.json".formatted(
                candidate.snapshotId(), candidate.candidateId()),
            output);
        return output;
    }
}
