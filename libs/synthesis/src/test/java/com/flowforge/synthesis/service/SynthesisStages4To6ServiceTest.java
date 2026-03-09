package com.flowforge.synthesis.service;

import com.flowforge.synthesis.TestFixtures;
import com.flowforge.synthesis.stage.DependencyMappingStage;
import com.flowforge.synthesis.stage.FinalNarrativeStage;
import com.flowforge.synthesis.stage.MigrationPlanStage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SynthesisStages4To6ServiceTest {

    @Mock
    DependencyMappingStage stage4;
    @Mock
    MigrationPlanStage stage5;
    @Mock
    FinalNarrativeStage stage6;

    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    SynthesisStages4To6Service service;

    @BeforeEach
    void setUp() {
        service = new SynthesisStages4To6Service(stage4, stage5, stage6, meterRegistry);
    }

    @Test
    void runStages4To6_chainsOutputsSequentially() {
        var candidate = TestFixtures.httpFlowCandidate();
        var partial = TestFixtures.samplePartialResult();
        var depMapping = TestFixtures.sampleDependencyMappingOutput();
        var migPlan = TestFixtures.sampleMigrationPlanOutput();
        var narrative = TestFixtures.sampleFinalNarrativeOutput();

        when(stage4.mapDependencies(candidate, partial)).thenReturn(depMapping);
        when(stage5.planMigration(candidate, partial, depMapping)).thenReturn(migPlan);
        when(stage6.generateNarrative(candidate, partial, depMapping, migPlan)).thenReturn(narrative);

        var result = service.runStages4To6(candidate, partial);

        assertThat(result.dependencyMapping()).isEqualTo(depMapping);
        assertThat(result.migrationPlan()).isEqualTo(migPlan);
        assertThat(result.narrative()).isEqualTo(narrative);

        InOrder inOrder = inOrder(stage4, stage5, stage6);
        inOrder.verify(stage4).mapDependencies(candidate, partial);
        inOrder.verify(stage5).planMigration(candidate, partial, depMapping);
        inOrder.verify(stage6).generateNarrative(candidate, partial, depMapping, migPlan);
    }
}
