package com.flowforge.synthesis.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.synthesis.TestFixtures;
import com.flowforge.synthesis.executor.SynthesisStageExecutor;
import com.flowforge.synthesis.model.MigrationPhase;
import com.flowforge.synthesis.model.MigrationPlanOutput;
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
class MigrationPlanStageTest {

    @Mock
    SynthesisStageExecutor executor;
    @Mock
    MinioStorageClient minio;

    MigrationPlanStage stage;

    @BeforeEach
    void setUp() {
        stage = new MigrationPlanStage(executor, minio, new ObjectMapper());
    }

    @Test
    void planMigration_injectsRiskAndDependencyContext() {
        var candidate = TestFixtures.httpFlowCandidate();
        var partial = TestFixtures.samplePartialResult();
        var depMapping = TestFixtures.sampleDependencyMappingOutput();
        when(executor.executeStage(eq("stage5"), eq(candidate),
            eq(MigrationPlanOutput.class), anyMap()))
            .thenReturn(TestFixtures.sampleMigrationPlanOutput());

        stage.planMigration(candidate, partial, depMapping);

        verify(executor).executeStage(eq("stage5"), eq(candidate),
            eq(MigrationPlanOutput.class),
            ArgumentMatchers.argThat(map -> map.containsKey("riskAssessment")
                && map.containsKey("dependencyMapping")));
    }

    @Test
    void planMigration_returnsOrderedPhases() {
        var candidate = TestFixtures.httpFlowCandidate();
        var plan = TestFixtures.sampleMigrationPlanOutput();
        when(executor.executeStage(anyString(), any(), eq(MigrationPlanOutput.class), anyMap()))
            .thenReturn(plan);

        var result = stage.planMigration(candidate,
            TestFixtures.samplePartialResult(),
            TestFixtures.sampleDependencyMappingOutput());

        assertThat(result.phases())
            .extracting(MigrationPhase::order)
            .isSorted();
    }
}
