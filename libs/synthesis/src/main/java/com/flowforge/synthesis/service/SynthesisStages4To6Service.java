package com.flowforge.synthesis.service;

import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.synthesis.model.SynthesisFullResult;
import com.flowforge.synthesis.model.SynthesisPartialResult;
import com.flowforge.synthesis.stage.DependencyMappingStage;
import com.flowforge.synthesis.stage.FinalNarrativeStage;
import com.flowforge.synthesis.stage.MigrationPlanStage;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * Runs synthesis stages 4–6 sequentially (dependency mapping, migration plan, final narrative).
 */
@Service
public class SynthesisStages4To6Service {

    private final DependencyMappingStage stage4;
    private final MigrationPlanStage stage5;
    private final FinalNarrativeStage stage6;
    private final MeterRegistry meterRegistry;

    public SynthesisStages4To6Service(DependencyMappingStage stage4,
                                      MigrationPlanStage stage5,
                                      FinalNarrativeStage stage6,
                                      MeterRegistry meterRegistry) {
        this.stage4 = stage4;
        this.stage5 = stage5;
        this.stage6 = stage6;
        this.meterRegistry = meterRegistry;
    }

    public SynthesisFullResult runStages4To6(FlowCandidate candidate,
                                              SynthesisPartialResult stages1to3) {
        var depMapping = meterRegistry.timer("flowforge.synthesis.stage4.latency")
            .record(() -> stage4.mapDependencies(candidate, stages1to3));

        var migrationPlan = meterRegistry.timer("flowforge.synthesis.stage5.latency")
            .record(() -> stage5.planMigration(candidate, stages1to3, depMapping));

        var narrative = meterRegistry.timer("flowforge.synthesis.stage6.latency")
            .record(() -> stage6.generateNarrative(candidate, stages1to3, depMapping, migrationPlan));

        return new SynthesisFullResult(
            candidate.candidateId(),
            stages1to3,
            depMapping,
            migrationPlan,
            narrative
        );
    }
}
