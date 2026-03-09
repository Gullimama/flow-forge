package com.flowforge.llm.config;

import com.flowforge.common.config.FlowForgeProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

@Configuration
public class LlmConfig {

    @Bean
    public ChatModel chatModel(FlowForgeProperties props, ObservationRegistry observationRegistry) {
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
