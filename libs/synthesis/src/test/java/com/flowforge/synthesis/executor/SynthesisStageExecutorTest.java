package com.flowforge.synthesis.executor;

import com.flowforge.synthesis.TestFixtures;
import com.flowforge.synthesis.model.CodeExplanationOutput;
import com.flowforge.synthesis.model.FlowAnalysisOutput;
import com.flowforge.llm.service.LlmGenerationService;
import com.flowforge.retrieval.service.HybridRetrievalService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SynthesisStageExecutorTest {

    @Mock
    LlmGenerationService llm;
    @Mock
    HybridRetrievalService retrieval;

    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SynthesisStageExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SynthesisStageExecutor(llm, retrieval, meterRegistry);
    }

    @Test
    void executeStage_buildsContextFromCandidate() {
        var candidate = TestFixtures.httpFlowCandidate();
        when(llm.generateStructured(anyString(), anyMap(), eq(FlowAnalysisOutput.class)))
            .thenReturn(TestFixtures.sampleFlowAnalysisOutput());

        var result = executor.executeStage("stage1", candidate, FlowAnalysisOutput.class);

        assertThat(result.flowName()).isEqualTo(candidate.flowName());
        verify(llm).generateStructured(eq("synthesis-stage1"), ArgumentMatchers.argThat(ctx ->
            ctx.containsKey("flowName")
                && ctx.containsKey("codeEvidence")
                && ctx.containsKey("logPatterns")
        ), eq(FlowAnalysisOutput.class));
    }

    @Test
    void executeStage_injectsPriorStageOutputs() {
        var candidate = TestFixtures.httpFlowCandidate();
        var priorOutputs = Map.<String, Object>of("flowAnalysis", "{\"flowName\":\"test\"}");
        when(llm.generateStructured(anyString(), anyMap(), eq(CodeExplanationOutput.class)))
            .thenReturn(TestFixtures.sampleCodeExplanationOutput());

        executor.executeStage("stage2", candidate, CodeExplanationOutput.class, priorOutputs);

        verify(llm).generateStructured(eq("synthesis-stage2"), ArgumentMatchers.argThat(ctx ->
            ctx.containsKey("flowAnalysis")), eq(CodeExplanationOutput.class));
    }

    @Test
    void generateWithRetry_retriesOnJsonParseFailure() {
        var candidate = TestFixtures.httpFlowCandidate();
        when(llm.generateStructured(anyString(), anyMap(), eq(FlowAnalysisOutput.class)))
            .thenThrow(new RuntimeException("JSON parse error"))
            .thenThrow(new RuntimeException("JSON parse error"))
            .thenReturn(TestFixtures.sampleFlowAnalysisOutput());

        var result = executor.executeStage("stage1", candidate, FlowAnalysisOutput.class);

        assertThat(result).isNotNull();
        verify(llm, times(3)).generateStructured(anyString(), anyMap(), eq(FlowAnalysisOutput.class));
    }

    @Test
    void generateWithRetry_throwsAfterMaxRetries() {
        var candidate = TestFixtures.httpFlowCandidate();
        when(llm.generateStructured(anyString(), anyMap(), eq(FlowAnalysisOutput.class)))
            .thenThrow(new RuntimeException("JSON parse error"));

        assertThatThrownBy(() ->
            executor.executeStage("stage1", candidate, FlowAnalysisOutput.class))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("failed after 3 attempts");
    }
}
