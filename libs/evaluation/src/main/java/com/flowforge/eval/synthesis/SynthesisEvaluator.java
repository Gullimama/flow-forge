package com.flowforge.eval.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.eval.model.EvaluationResult;
import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.retrieval.model.RankedDocument;
import com.flowforge.synthesis.model.FinalNarrativeOutput;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

@Component
public class SynthesisEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SynthesisEvaluator.class);

    private final ChatModel chatModel;

    public SynthesisEvaluator(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * Evaluate synthesis quality using LLM-as-judge and heuristic checks.
     */
    public EvaluationResult.SynthesisEvaluation evaluate(
        FlowCandidate flow,
        FinalNarrativeOutput synthesis,
        List<RankedDocument> sourceEvidence
    ) {
        var issues = new ArrayList<String>();

        String narrative = synthesis.detailedNarrative();

        double factualConsistency = evaluateFactualConsistency(narrative, sourceEvidence, issues);
        double completeness = evaluateCompleteness(flow, narrative, issues);
        double coherence = evaluateCoherence(narrative, issues);
        double technicalAccuracy = evaluateTechnicalAccuracy(narrative, flow, issues);

        double overall = (factualConsistency * 0.3
            + completeness * 0.25
            + coherence * 0.2
            + technicalAccuracy * 0.25);

        return new EvaluationResult.SynthesisEvaluation(
            flow.flowName(),
            factualConsistency,
            completeness,
            coherence,
            technicalAccuracy,
            overall,
            issues
        );
    }

    private double evaluateFactualConsistency(
        String narrative,
        List<RankedDocument> sources,
        List<String> issues
    ) {
        var sb = new StringBuilder();
        sources.stream().limit(5).forEach(d -> {
            sb.append("- ").append(truncate(d.content(), 256)).append("\n");
        });
        String prompt = """
            You are evaluating factual consistency of a technical document.
            Rate how well the narrative is supported by the source evidence.

            NARRATIVE:
            %s

            SOURCE EVIDENCE (first 5):
            %s

            Return a JSON object: {"score": 0.0-1.0, "issues": ["..."]}
            """.formatted(truncate(narrative, 2000), sb);

        return callJudge(prompt, issues);
    }

    private double evaluateCompleteness(
        FlowCandidate flow,
        String narrative,
        List<String> issues
    ) {
        long coveredSteps = flow.steps().stream()
            .filter(step -> narrative.contains(step.serviceName()))
            .count();
        double score = flow.steps().isEmpty()
            ? 1.0
            : (double) coveredSteps / flow.steps().size();
        if (!flow.steps().isEmpty() && score < 0.8) {
            issues.add("Missing coverage for %d/%d flow steps"
                .formatted(flow.steps().size() - coveredSteps, flow.steps().size()));
        }
        return score;
    }

    private double evaluateCoherence(String narrative, List<String> issues) {
        var sections = narrative.split("(?m)^#{1,3} ");
        double sectionScore = Math.min(1.0, sections.length / 5.0);

        var connectors = List.of("therefore", "because", "as a result",
            "consequently", "this means", "next", "then", "finally");
        long connectorCount = connectors.stream()
            .filter(c -> narrative.toLowerCase().contains(c))
            .count();
        double connectorScore = Math.min(1.0, connectorCount / 4.0);

        return (sectionScore + connectorScore) / 2.0;
    }

    private double evaluateTechnicalAccuracy(
        String narrative,
        FlowCandidate flow,
        List<String> issues
    ) {
        var expectedTerms = new HashSet<String>();
        flow.steps().forEach(step -> {
            expectedTerms.add(step.serviceName());
            expectedTerms.addAll(step.annotations());
        });

        long found = expectedTerms.stream()
            .filter(narrative::contains)
            .count();

        return expectedTerms.isEmpty() ? 1.0
            : (double) found / expectedTerms.size();
    }

    private double callJudge(String prompt, List<String> issues) {
        try {
            var response = chatModel.call(new Prompt(prompt));
            var generation = response.getResult();
            String json = generation != null && generation.getOutput() != null
                ? generation.getOutput().getText()
                : "";
            json = json.replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*$", "")
                .trim();
            var node = new ObjectMapper().readTree(json);
            var judgeIssues = node.get("issues");
            if (judgeIssues != null && judgeIssues.isArray()) {
                judgeIssues.forEach(i -> issues.add(i.asText()));
            }
            return node.has("score") ? node.get("score").asDouble() : 0.5;
        } catch (Exception e) {
            log.warn("LLM judge call failed: {}", e.getMessage());
            issues.add("LLM judge unavailable: " + e.getMessage());
            return 0.5;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }
}

