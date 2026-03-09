package com.flowforge.synthesis.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.synthesis.TestFixtures;
import com.flowforge.synthesis.executor.SynthesisStageExecutor;
import com.flowforge.synthesis.model.RiskAssessmentOutput;
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
class RiskAssessmentStageTest {

    @Mock
    SynthesisStageExecutor executor;
    @Mock
    MinioStorageClient minio;

    RiskAssessmentStage stage;

    @BeforeEach
    void setUp() {
        stage = new RiskAssessmentStage(executor, minio, new ObjectMapper());
    }

    @Test
    void assess_injectsBothPriorStageOutputs() {
        var candidate = TestFixtures.httpFlowCandidate();
        var analysis = TestFixtures.sampleFlowAnalysisOutput();
        var explanation = TestFixtures.sampleCodeExplanationOutput();
        when(executor.executeStage(eq("stage3"), eq(candidate),
            eq(RiskAssessmentOutput.class), anyMap()))
            .thenReturn(TestFixtures.sampleRiskAssessmentOutput());

        stage.assess(candidate, analysis, explanation);

        verify(executor).executeStage(eq("stage3"), eq(candidate),
            eq(RiskAssessmentOutput.class),
            ArgumentMatchers.argThat(map ->
                map.containsKey("flowAnalysis") && map.containsKey("codeExplanation")));
    }
}
