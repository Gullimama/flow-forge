package com.flowforge.llm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.llm.exception.LlmGenerationException;
import com.flowforge.llm.output.StructuredOutputService;
import com.flowforge.llm.prompt.PromptTemplateManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.Map;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

/**
 * Resilient LLM generation: free-form and structured, with circuit breaker, retry, and metrics.
 */
@Service
public class LlmGenerationService {

    private static final Logger log = LoggerFactory.getLogger(LlmGenerationService.class);

    private final ChatModel chatModel;
    private final StructuredOutputService structuredOutput;
    private final PromptTemplateManager promptManager;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public LlmGenerationService(ChatModel chatModel,
                                StructuredOutputService structuredOutput,
                                PromptTemplateManager promptManager,
                                MeterRegistry meterRegistry,
                                ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.structuredOutput = structuredOutput;
        this.promptManager = promptManager;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    @CircuitBreaker(name = "vllm", fallbackMethod = "fallbackGenerate")
    @Retry(name = "vllm")
    public String generate(String templateName, Map<String, Object> variables) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var prompt = promptManager.render(templateName, variables);
            var response = chatModel.call(prompt);

            ChatResponseMetadata metadata = response.getMetadata();
            if (metadata != null && metadata.getUsage() != null) {
                Integer promptTokens = metadata.getUsage().getPromptTokens();
                Integer completionTokens = metadata.getUsage().getCompletionTokens();
                if (promptTokens != null) {
                    meterRegistry.counter("flowforge.llm.tokens.prompt").increment(promptTokens);
                }
                if (completionTokens != null) {
                    meterRegistry.counter("flowforge.llm.tokens.completion").increment(completionTokens);
                }
            }

            var result = response.getResult();
            String content = (result != null && result.getOutput() != null)
                ? result.getOutput().getText()
                : "";
            return content;
        } finally {
            sample.stop(meterRegistry.timer("flowforge.llm.generation.latency", "template", templateName));
        }
    }

    @CircuitBreaker(name = "vllm", fallbackMethod = "fallbackStructured")
    @Retry(name = "vllm")
    public <T> T generateStructured(String templateName, Map<String, Object> variables, Class<T> outputType) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return structuredOutput.generate(templateName, variables, outputType);
        } finally {
            sample.stop(meterRegistry.timer("flowforge.llm.generation.structured.latency", "template", templateName));
        }
    }

    @SuppressWarnings("unused")
    private String fallbackGenerate(String templateName, Map<String, Object> variables, Throwable t) {
        log.error("LLM generation failed for template {}: {}", templateName, t.getMessage());
        meterRegistry.counter("flowforge.llm.generation.fallback").increment();
        return "[LLM generation unavailable — template: %s, error: %s]"
            .formatted(templateName, t.getMessage());
    }

    @SuppressWarnings("unused")
    private <T> T fallbackStructured(String templateName, Map<String, Object> variables, Class<T> outputType, Throwable t) {
        log.error("Structured LLM generation failed for {}: {}", templateName, t.getMessage());
        meterRegistry.counter("flowforge.llm.generation.structured.fallback").increment();
        throw new LlmGenerationException(
            "Structured generation failed for template '%s': %s".formatted(templateName, t.getMessage()), t);
    }

    private String serializeToString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
