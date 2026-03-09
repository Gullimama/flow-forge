package com.flowforge.synthesis.stage;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.synthesis.TestFixtures;
import com.flowforge.synthesis.executor.SynthesisStageExecutor;
import com.flowforge.synthesis.model.FlowAnalysisOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlowAnalysisStageTest {

    @Mock
    SynthesisStageExecutor executor;
    @Mock
    MinioStorageClient minio;

    @InjectMocks
    FlowAnalysisStage stage;

    @Test
    void analyze_delegatesToExecutorAndStoresResult() {
        var candidate = TestFixtures.httpFlowCandidate();
        var expected = TestFixtures.sampleFlowAnalysisOutput();
        when(executor.executeStage("stage1", candidate, FlowAnalysisOutput.class))
            .thenReturn(expected);

        var result = stage.analyze(candidate);

        assertThat(result).isEqualTo(expected);
        verify(minio).putJson(eq("evidence"),
            ArgumentMatchers.argThat(path -> path != null && path.contains("synthesis/stage1/")),
            eq(expected));
    }
}
