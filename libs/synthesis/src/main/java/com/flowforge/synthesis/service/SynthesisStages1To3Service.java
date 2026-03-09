package com.flowforge.synthesis.service;

import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.synthesis.model.SynthesisPartialResult;
import com.flowforge.synthesis.stage.CodeExplanationStage;
import com.flowforge.synthesis.stage.FlowAnalysisStage;
import com.flowforge.synthesis.stage.RiskAssessmentStage;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs synthesis stages 1–3 sequentially per flow, parallel across flows (virtual threads).
 */
@Service
public class SynthesisStages1To3Service {

    private static final Logger log = LoggerFactory.getLogger(SynthesisStages1To3Service.class);

    private final FlowAnalysisStage stage1;
    private final CodeExplanationStage stage2;
    private final RiskAssessmentStage stage3;
    private final MeterRegistry meterRegistry;

    public SynthesisStages1To3Service(FlowAnalysisStage stage1,
                                      CodeExplanationStage stage2,
                                      RiskAssessmentStage stage3,
                                      MeterRegistry meterRegistry) {
        this.stage1 = stage1;
        this.stage2 = stage2;
        this.stage3 = stage3;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Run stages 1–3 sequentially for a flow candidate.
     */
    public SynthesisPartialResult runStages1To3(FlowCandidate candidate) {
        var flowAnalysis = meterRegistry.timer("flowforge.synthesis.stage1.latency")
            .record(() -> stage1.analyze(candidate));
        var codeExplanation = meterRegistry.timer("flowforge.synthesis.stage2.latency")
            .record(() -> stage2.explain(candidate, flowAnalysis));
        var riskAssessment = meterRegistry.timer("flowforge.synthesis.stage3.latency")
            .record(() -> stage3.assess(candidate, flowAnalysis, codeExplanation));
        return new SynthesisPartialResult(
            candidate.candidateId(),
            flowAnalysis,
            codeExplanation,
            riskAssessment
        );
    }

    /**
     * Run stages 1–3 for all flow candidates in parallel (virtual threads).
     */
    public List<SynthesisPartialResult> runAllCandidates(List<FlowCandidate> candidates) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = candidates.stream()
                .map(c -> executor.submit(() -> runStages1To3(c)))
                .toList();
            return futures.stream()
                .map(f -> {
                    try {
                        return f.get();
                    } catch (Exception e) {
                        log.error("Synthesis failed for candidate: {}", e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
        }
    }
}
