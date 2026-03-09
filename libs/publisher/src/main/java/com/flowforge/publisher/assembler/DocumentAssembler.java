package com.flowforge.publisher.assembler;

import com.flowforge.publisher.model.AppendixItem;
import com.flowforge.publisher.model.ExecutiveSummary;
import com.flowforge.publisher.model.FlowSection;
import com.flowforge.publisher.model.MigrationRoadmap;
import com.flowforge.publisher.model.ResearchDocument;
import com.flowforge.publisher.model.RiskMatrix;
import com.flowforge.publisher.model.RiskMatrixEntry;
import com.flowforge.publisher.model.DocumentMetadata;
import com.flowforge.publisher.model.RoadmapPhase;
import com.flowforge.synthesis.model.FinalNarrativeOutput;
import com.flowforge.synthesis.model.SynthesisFullResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Assembles a ResearchDocument from synthesis results.
 */
@Service
public class DocumentAssembler {

    /**
     * Assemble a ResearchDocument from synthesis results.
     */
    public ResearchDocument assemble(UUID snapshotId, List<SynthesisFullResult> results) {
        var flowSections = results.stream()
            .map(this::buildFlowSection)
            .sorted(Comparator.comparing(FlowSection::flowName))
            .toList();

        var riskMatrix = buildRiskMatrix(results);
        var roadmap = buildRoadmap(results);
        var summary = buildExecutiveSummary(flowSections, riskMatrix);

        return new ResearchDocument(
            "System Flows Research — Migration Analysis",
            Instant.now(),
            snapshotId,
            summary,
            flowSections,
            riskMatrix,
            roadmap,
            buildAppendices(results),
            new DocumentMetadata("Qwen2.5-Coder-32B-Instruct", 0, 0)
        );
    }

    public String slugify(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    }

    private FlowSection buildFlowSection(SynthesisFullResult result) {
        return new FlowSection(
            result.narrative().flowName(),
            slugify(result.narrative().flowName()),
            result.stages1to3().flowAnalysis(),
            result.stages1to3().codeExplanation(),
            result.stages1to3().riskAssessment(),
            result.dependencyMapping(),
            result.migrationPlan(),
            result.narrative()
        );
    }

    private RiskMatrix buildRiskMatrix(List<SynthesisFullResult> results) {
        var entries = results.stream()
            .flatMap(r -> r.stages1to3().riskAssessment().risks().stream()
                .map(risk -> new RiskMatrixEntry(
                    r.narrative().flowName(),
                    risk.description(),
                    risk.severity().name(),
                    risk.category(),
                    risk.mitigation()
                )))
            .toList();

        Map<String, Long> bySeverity = entries.stream()
            .collect(Collectors.groupingBy(RiskMatrixEntry::severity, Collectors.counting()));
        Map<String, Long> byCategory = entries.stream()
            .collect(Collectors.groupingBy(RiskMatrixEntry::category, Collectors.counting()));

        return new RiskMatrix(entries, bySeverity, byCategory);
    }

    private MigrationRoadmap buildRoadmap(List<SynthesisFullResult> results) {
        var phases = new ArrayList<RoadmapPhase>();
        int order = 1;
        String totalDuration = "TBD";
        String teamSize = "TBD";

        for (var result : results) {
            var plan = result.migrationPlan();
            if (plan.phases() != null) {
                for (var p : plan.phases()) {
                    phases.add(new RoadmapPhase(
                        order++,
                        p.phaseName(),
                        p.estimatedDuration(),
                        List.of(result.narrative().flowName()),
                        p.deliverables() != null ? p.deliverables() : List.of()
                    ));
                }
            }
            if (plan.effort() != null && totalDuration.equals("TBD")) {
                totalDuration = plan.effort().totalDuration();
                teamSize = String.valueOf(plan.effort().teamSize());
            }
        }

        return new MigrationRoadmap(phases, totalDuration, teamSize);
    }

    private ExecutiveSummary buildExecutiveSummary(
            List<FlowSection> flowSections,
            RiskMatrix riskMatrix) {
        int totalFlows = flowSections.size();
        Set<String> services = flowSections.stream()
            .flatMap(f -> f.dependencyMapping().runtimeDependencies().stream()
                .map(com.flowforge.synthesis.model.RuntimeDependency::serviceName))
            .collect(Collectors.toSet());
        int totalServices = services.size();
        int criticalRisks = riskMatrix.bySeverity().getOrDefault("CRITICAL", 0L).intValue();
        int highRisks = riskMatrix.bySeverity().getOrDefault("HIGH", 0L).intValue();

        var topFindings = flowSections.stream()
            .map(f -> f.narrative().executiveSummary())
            .filter(s -> s != null && !s.isBlank())
            .limit(5)
            .toList();

        var recommendedApproach = "Review risk matrix and migration roadmap.";
        if (!flowSections.isEmpty()) {
            var first = flowSections.get(0).narrative().recommendedNextSteps();
            if (first != null && !first.isBlank()) {
                recommendedApproach = first;
            }
        }

        var overview = "Analysis of %d flow(s) across %d service(s)."
            .formatted(totalFlows, totalServices);
        if (!flowSections.isEmpty()) {
            var firstSummary = flowSections.get(0).narrative().executiveSummary();
            if (firstSummary != null && !firstSummary.isBlank()) {
                overview = firstSummary;
            }
        }

        return new ExecutiveSummary(
            overview,
            totalFlows,
            totalServices,
            criticalRisks,
            highRisks,
            topFindings.isEmpty() ? List.of("No findings summarized.") : topFindings,
            recommendedApproach
        );
    }

    private List<AppendixItem> buildAppendices(List<SynthesisFullResult> results) {
        return List.of();
    }
}
