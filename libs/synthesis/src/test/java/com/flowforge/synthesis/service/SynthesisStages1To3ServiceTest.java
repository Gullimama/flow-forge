package com.flowforge.synthesis.service;

import com.flowforge.synthesis.TestFixtures;
import com.flowforge.synthesis.stage.CodeExplanationStage;
import com.flowforge.synthesis.stage.FlowAnalysisStage;
import com.flowforge.synthesis.stage.RiskAssessmentStage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SynthesisStages1To3ServiceTest {

    @Mock
    FlowAnalysisStage stage1;
    @Mock
    CodeExplanationStage stage2;
    @Mock
    RiskAssessmentStage stage3;

    MeterRegistry meterRegistry = new SimpleMeterRegistry();

    SynthesisStages1To3Service service;

    @BeforeEach
    void setUp() {
        service = new SynthesisStages1To3Service(stage1, stage2, stage3, meterRegistry);
    }

    @Test
    void runStages1To3_executesSequentially() {
        var candidate = TestFixtures.httpFlowCandidate();
        var analysis = TestFixtures.sampleFlowAnalysisOutput();
        var explanation = TestFixtures.sampleCodeExplanationOutput();
        var risk = TestFixtures.sampleRiskAssessmentOutput();

        when(stage1.analyze(candidate)).thenReturn(analysis);
        when(stage2.explain(candidate, analysis)).thenReturn(explanation);
        when(stage3.assess(candidate, analysis, explanation)).thenReturn(risk);

        var result = service.runStages1To3(candidate);

        assertThat(result.flowAnalysis()).isEqualTo(analysis);
        assertThat(result.codeExplanation()).isEqualTo(explanation);
        assertThat(result.riskAssessment()).isEqualTo(risk);

        InOrder inOrder = inOrder(stage1, stage2, stage3);
        inOrder.verify(stage1).analyze(candidate);
        inOrder.verify(stage2).explain(candidate, analysis);
        inOrder.verify(stage3).assess(candidate, analysis, explanation);
    }

    @Test
    void runAllCandidates_executesFlowsInParallel() {
        var candidates = List.of(
            TestFixtures.httpFlowCandidate(),
            TestFixtures.kafkaFlowCandidate(),
            TestFixtures.grpcFlowCandidate());

        for (var c : candidates) {
            when(stage1.analyze(c)).thenReturn(TestFixtures.sampleFlowAnalysisOutput());
            when(stage2.explain(eq(c), any())).thenReturn(TestFixtures.sampleCodeExplanationOutput());
            when(stage3.assess(eq(c), any(), any())).thenReturn(TestFixtures.sampleRiskAssessmentOutput());
        }

        var results = service.runAllCandidates(candidates);

        assertThat(results).hasSize(3);
    }

    @Test
    void runAllCandidates_continuesWhenOneFlowFails() {
        var candidates = List.of(
            TestFixtures.httpFlowCandidate(),
            TestFixtures.kafkaFlowCandidate());

        when(stage1.analyze(candidates.get(0)))
            .thenThrow(new RuntimeException("LLM timeout"));
        when(stage1.analyze(candidates.get(1)))
            .thenReturn(TestFixtures.sampleFlowAnalysisOutput());
        when(stage2.explain(eq(candidates.get(1)), any()))
            .thenReturn(TestFixtures.sampleCodeExplanationOutput());
        when(stage3.assess(eq(candidates.get(1)), any(), any()))
            .thenReturn(TestFixtures.sampleRiskAssessmentOutput());

        var results = service.runAllCandidates(candidates);

        assertThat(results).hasSize(1);
    }
}
