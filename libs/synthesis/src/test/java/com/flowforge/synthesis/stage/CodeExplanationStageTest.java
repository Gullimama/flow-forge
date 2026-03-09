package com.flowforge.synthesis.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.synthesis.TestFixtures;
import com.flowforge.synthesis.executor.SynthesisStageExecutor;
import com.flowforge.synthesis.model.CodeExplanationOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeExplanationStageTest {

    @Mock
    SynthesisStageExecutor executor;
    @Mock
    MinioStorageClient minio;

    CodeExplanationStage stage;

    @BeforeEach
    void setUp() {
        stage = new CodeExplanationStage(executor, minio, new ObjectMapper());
    }

    @Test
    void explain_passesPriorFlowAnalysisAsContext() {
        var candidate = TestFixtures.httpFlowCandidate();
        var flowAnalysis = TestFixtures.sampleFlowAnalysisOutput();
        when(executor.executeStage(eq("stage2"), eq(candidate),
            eq(CodeExplanationOutput.class), anyMap()))
            .thenReturn(TestFixtures.sampleCodeExplanationOutput());

        stage.explain(candidate, flowAnalysis);

        verify(executor).executeStage(eq("stage2"), eq(candidate),
            eq(CodeExplanationOutput.class),
            ArgumentMatchers.argThat(map -> map.containsKey("priorStageOutput")));
    }
}
