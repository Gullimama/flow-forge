package com.flowforge.llm.config;

import com.flowforge.common.config.FlowForgeProperties;
import io.micrometer.observation.ObservationRegistry;
import java.util.Collections;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    @Bean
    public ChatModel chatModel(FlowForgeProperties props, ObservationRegistry observationRegistry) {
        if (props.ollama() != null && props.ollama().baseUrl() != null && !props.ollama().baseUrl().isBlank()) {
            String baseUrl = props.ollama().baseUrl();
            String modelName = props.ollama().chatModel() != null && !props.ollama().chatModel().isBlank()
                ? props.ollama().chatModel()
                : "llama3.1";

            var api = OllamaApi.builder()
                .baseUrl(baseUrl)
                .build();

            var options = OllamaOptions.builder()
                .model(modelName)
                .temperature(0.1)
                .build();

            ToolCallingManager toolCallingManager = DefaultToolCallingManager.builder()
                .observationRegistry(observationRegistry)
                .toolCallbackResolver(new StaticToolCallbackResolver(Collections.emptyList()))
                .toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(false))
                .build();

            return new OllamaChatModel(api, options, toolCallingManager, observationRegistry, ModelManagementOptions.defaults());
        }

        String baseUrl = props.vllm() != null && props.vllm().baseUrl() != null && !props.vllm().baseUrl().isBlank()
            ? props.vllm().baseUrl()
            : "http://localhost:8000";

        var api = OpenAiApi.builder()
            .baseUrl(baseUrl)
            .apiKey("not-needed")
            .build();

        var options = OpenAiChatOptions.builder()
            .model(props.vllm() != null && props.vllm().model() != null ? props.vllm().model() : "Qwen/Qwen2.5-Coder-32B-Instruct")
            .temperature(0.1)
            .maxTokens(8192)
            .topP(0.95)
            .frequencyPenalty(0.1)
            .build();

        return new OpenAiChatModel(api, options, null, null, observationRegistry);
    }
}
