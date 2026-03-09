package com.flowforge.synthesis.executor;

import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.flow.model.FlowStep;
import com.flowforge.llm.service.LlmGenerationService;
import com.flowforge.retrieval.service.HybridRetrievalService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Executes a single synthesis stage: builds context from a flow candidate,
 * injects prior stage outputs, and calls the LLM for structured output.
 */
@Service
public class SynthesisStageExecutor {

    private static final Logger log = LoggerFactory.getLogger(SynthesisStageExecutor.class);

    private final LlmGenerationService llm;
    private final HybridRetrievalService retrieval;
    private final MeterRegistry meterRegistry;

    public SynthesisStageExecutor(LlmGenerationService llm,
                                  HybridRetrievalService retrieval,
                                  MeterRegistry meterRegistry) {
        this.llm = llm;
        this.retrieval = retrieval;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Execute a synthesis stage for a single flow candidate.
     */
    public <T> T executeStage(String stageName, FlowCandidate candidate, Class<T> outputType) {
        return executeStage(stageName, candidate, outputType, Map.of());
    }

    /**
     * Execute a synthesis stage with prior stage outputs injected into the prompt context.
     */
    public <T> T executeStage(String stageName, FlowCandidate candidate,
                               Class<T> outputType,
                               Map<String, Object> priorStageOutputs) {
        var context = buildContext(candidate);
        context.putAll(priorStageOutputs);
        var additionalContext = retrieveAdditionalContext(stageName, candidate);
        context.putAll(additionalContext);
        return generateWithRetry(stageName, context, outputType, 2);
    }

    /**
     * Retry LLM generation up to maxRetries on JSON parse failures.
     */
    private <T> T generateWithRetry(String stageName, Map<String, Object> context,
                                    Class<T> outputType, int maxRetries) {
        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return llm.generateStructured("synthesis-" + stageName, context, outputType);
            } catch (Exception e) {
                lastException = e;
                log.warn("Synthesis stage {} attempt {} failed: {}", stageName, attempt + 1, e.getMessage());
            }
        }
        throw new RuntimeException("Synthesis stage %s failed after %d attempts"
            .formatted(stageName, maxRetries + 1), lastException);
    }

    private Map<String, Object> buildContext(FlowCandidate candidate) {
        var context = new HashMap<String, Object>();
        context.put("flowName", candidate.flowName());
        context.put("flowType", candidate.flowType().name());
        context.put("services", String.join(", ", candidate.involvedServices()));
        context.put("steps", formatSteps(candidate.steps()));
        context.put("codeEvidence", String.join("\n---\n", candidate.evidence().codeSnippets()));
        context.put("logPatterns", String.join("\n", candidate.evidence().logPatterns()));
        context.put("graphContext", String.join("\n", candidate.evidence().graphPaths()));
        context.put("complexity", candidate.complexity().name());
        return context;
    }

    private Map<String, Object> retrieveAdditionalContext(String stageName, FlowCandidate candidate) {
        return Map.of();
    }

    private static String formatSteps(java.util.List<FlowStep> steps) {
        return steps.stream()
            .map(s -> "%s → %s (%s)".formatted(
                s.serviceName(), s.action(), s.stepType()))
            .collect(Collectors.joining("\n"));
    }
}
