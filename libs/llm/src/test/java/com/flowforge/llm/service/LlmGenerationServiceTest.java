package com.flowforge.llm.service;

import com.flowforge.llm.TestFixtures;
import com.flowforge.llm.exception.LlmGenerationException;
import com.flowforge.llm.output.StructuredOutputService;
import com.flowforge.llm.prompt.PromptTemplateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmGenerationServiceTest {

    @Mock
    ChatModel chatModel;
    @Mock
    StructuredOutputService structuredOutput;
    @Mock
    PromptTemplateManager promptManager;
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @Mock
    ObjectMapper objectMapper;

    @InjectMocks
    LlmGenerationService generationService;

    @BeforeEach
    void initService() {
        generationService = new LlmGenerationService(
            chatModel, structuredOutput, promptManager, meterRegistry, objectMapper);
    }

    @Test
    void generate_returnsLlmContent() {
        when(promptManager.render(anyString(), any())).thenReturn(new Prompt("test"));
        when(chatModel.call(any(Prompt.class))).thenReturn(
            TestFixtures.chatResponseWithTokens("Generated analysis", 500, 200));

        var result = generationService.generate("flow-analysis", Map.of());
        assertThat(result).isEqualTo("Generated analysis");
    }

    @Test
    void generate_tracksTokenUsage() {
        when(promptManager.render(anyString(), any())).thenReturn(new Prompt("test"));
        when(chatModel.call(any(Prompt.class))).thenReturn(
            TestFixtures.chatResponseWithTokens("result", 1000, 500));

        generationService.generate("flow-analysis", Map.of());

        assertThat(meterRegistry.find("flowforge.llm.tokens.prompt").counter()).isNotNull();
        assertThat(meterRegistry.find("flowforge.llm.tokens.completion").counter()).isNotNull();
        assertThat(meterRegistry.find("flowforge.llm.tokens.prompt").counter().count()).isEqualTo(1000);
        assertThat(meterRegistry.find("flowforge.llm.tokens.completion").counter().count()).isEqualTo(500);
    }

    @Test
    void fallbackGenerate_returnsErrorMessage() throws Exception {
        LlmGenerationService service = new LlmGenerationService(
            chatModel, structuredOutput, promptManager, meterRegistry, objectMapper);
        var method = LlmGenerationService.class.getDeclaredMethod(
            "fallbackGenerate", String.class, Map.class, Throwable.class);
        method.setAccessible(true);

        var result = (String) method.invoke(service,
            "flow-analysis", Map.of(), new RuntimeException("vLLM timeout"));

        assertThat(result).contains("[LLM generation unavailable");
        assertThat(result).contains("flow-analysis");
        assertThat(result).contains("vLLM timeout");
    }

    @Test
    void fallbackStructured_throwsLlmGenerationException() throws Exception {
        LlmGenerationService service = new LlmGenerationService(
            chatModel, structuredOutput, promptManager, meterRegistry, objectMapper);
        var method = LlmGenerationService.class.getDeclaredMethod(
            "fallbackStructured", String.class, Map.class, Class.class, Throwable.class);
        method.setAccessible(true);

        assertThatThrownBy(() ->
            method.invoke(service, "flow-analysis", Map.of(), String.class, new RuntimeException("down")))
            .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
            .hasCauseInstanceOf(LlmGenerationException.class);
    }
}
