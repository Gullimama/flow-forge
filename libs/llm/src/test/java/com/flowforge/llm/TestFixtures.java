package com.flowforge.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test fixtures for LLM unit and integration tests.
 */
public final class TestFixtures {

    private TestFixtures() {}

    /**
     * Build a ChatResponse with the given assistant text content.
     */
    public static ChatResponse chatResponse(String content) {
        return new ChatResponse(
            List.of(new Generation(new AssistantMessage(content))),
            ChatResponseMetadata.builder().build()
        );
    }

    /**
     * Build a ChatResponse with content and token usage for metrics tests.
     */
    public static ChatResponse chatResponseWithTokens(String content, int promptTokens, int completionTokens) {
        return new ChatResponse(
            List.of(new Generation(new AssistantMessage(content))),
            ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build()
        );
    }

    /**
     * OpenAI-compatible /v1/chat/completions response JSON for WireMock.
     * Content is escaped for JSON string (backslash, quote, newline).
     */
    public static String openAiChatCompletionResponse(String content, int promptTokens, int completionTokens) {
        String escaped = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
        return """
            {
              "id": "gen-test",
              "object": "chat.completion",
              "created": 1234567890,
              "model": "Qwen/Qwen2.5-Coder-32B-Instruct",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "%s"
                  },
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": %d,
                "completion_tokens": %d,
                "total_tokens": %d
              }
            }
            """.formatted(escaped, promptTokens, completionTokens, promptTokens + completionTokens);
    }

    /**
     * Minimal template variables so that a named template can be rendered without missing placeholders.
     */
    public static Map<String, Object> minimalTemplateVars(String templateName) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("format", "{}");
        switch (templateName) {
            case "flow-analysis", "synthesis-stage1" -> {
                vars.put("flowName", "test-flow");
                vars.put("flowType", "SYNC_REQUEST");
                vars.put("services", "svc-a, svc-b");
                vars.put("codeEvidence", "class Foo {}");
                vars.put("logPatterns", "ERROR: timeout");
                vars.put("graphContext", "svc-a -> svc-b");
                vars.put("complexity", "LOW");
            }
            case "code-explanation" -> {
                vars.put("flowName", "test-flow");
                vars.put("complexity", "LOW");
                vars.put("codeEvidence", "class Foo {}");
                vars.put("flowAnalysis", "{}");
            }
            case "migration-risk", "synthesis-stage3" -> {
                vars.put("flowName", "test-flow");
                vars.put("services", "svc-a");
                vars.put("codeEvidence", "class Foo {}");
                vars.put("flowAnalysis", "{}");
                vars.put("codeExplanation", "{}");
            }
            case "dependency-analysis", "synthesis-stage4" -> {
                vars.put("flowName", "test-flow");
                vars.put("services", "svc-a");
                vars.put("codeEvidence", "{}");
                vars.put("graphContext", "{}");
                vars.put("priorStageOutput", "{}");
                vars.put("buildEvidence", "{}");
            }
            case "reactive-complexity" -> {
                vars.put("classFqn", "com.example.Foo");
                vars.put("serviceName", "svc-a");
                vars.put("sourceCode", "class Foo {}");
                vars.put("methodDetails", "{}");
            }
            case "synthesis-stage2" -> {
                vars.put("flowName", "test-flow");
                vars.put("priorStageOutput", "{}");
                vars.put("codeEvidence", "{}");
            }
            case "synthesis-stage5" -> {
                vars.put("flowName", "test-flow");
                vars.put("riskAssessment", "{}");
                vars.put("dependencyMapping", "{}");
                vars.put("services", "svc-a");
                vars.put("complexity", "LOW");
            }
            case "synthesis-stage6" -> {
                vars.put("flowName", "test-flow");
                vars.put("flowAnalysis", "{}");
                vars.put("codeExplanation", "{}");
                vars.put("riskAssessment", "{}");
                vars.put("dependencyMapping", "{}");
                vars.put("migrationPlan", "{}");
            }
            default -> {
                vars.put("flowName", "test-flow");
                vars.put("services", "svc-a");
                vars.put("codeEvidence", "{}");
                vars.put("graphContext", "{}");
            }
        }
        return vars;
    }
}
