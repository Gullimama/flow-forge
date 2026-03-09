package com.flowforge.synthesis.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.synthesis.TestFixtures;
import com.flowforge.synthesis.executor.SynthesisStageExecutor;
import com.flowforge.synthesis.model.FinalNarrativeOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinalNarrativeStageTest {

    @Mock
    SynthesisStageExecutor executor;
    @Mock
    MinioStorageClient minio;

    FinalNarrativeStage stage;

    @BeforeEach
    void setUp() {
        stage = new FinalNarrativeStage(executor, minio, new ObjectMapper());
    }

    @Test
    void generateNarrative_injectsAllPriorStageOutputs() {
        var candidate = TestFixtures.httpFlowCandidate();
        var partial = TestFixtures.samplePartialResult();
        var depMapping = TestFixtures.sampleDependencyMappingOutput();
        var migrationPlan = TestFixtures.sampleMigrationPlanOutput();
        when(executor.executeStage(eq("stage6"), eq(candidate),
            eq(FinalNarrativeOutput.class), anyMap()))
            .thenReturn(TestFixtures.sampleFinalNarrativeOutput());

        stage.generateNarrative(candidate, partial, depMapping, migrationPlan);

        verify(executor).executeStage(eq("stage6"), eq(candidate),
            eq(FinalNarrativeOutput.class),
            ArgumentMatchers.argThat(map -> map.containsKey("flowAnalysis")
                && map.containsKey("codeExplanation")
                && map.containsKey("riskAssessment")
                && map.containsKey("dependencyMapping")
                && map.containsKey("migrationPlan")));
    }

    @Test
    void generateNarrative_includesMermaidDiagrams() {
        var candidate = TestFixtures.httpFlowCandidate();
        var narrative = TestFixtures.sampleFinalNarrativeOutput();
        when(executor.executeStage(anyString(), any(), eq(FinalNarrativeOutput.class), anyMap()))
            .thenReturn(narrative);

        var result = stage.generateNarrative(candidate,
            TestFixtures.samplePartialResult(),
            TestFixtures.sampleDependencyMappingOutput(),
            TestFixtures.sampleMigrationPlanOutput());

        assertThat(result.diagrams()).isNotEmpty();
        assertThat(result.diagrams().get(0).mermaidCode()).contains("sequenceDiagram");
    }
}
