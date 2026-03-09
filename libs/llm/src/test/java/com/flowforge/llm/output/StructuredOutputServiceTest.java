package com.flowforge.llm.output;

import com.flowforge.llm.TestFixtures;
import com.flowforge.llm.prompt.PromptTemplateManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StructuredOutputServiceTest {

    @Mock
    ChatModel chatModel;
    @Mock
    PromptTemplateManager promptManager;
    @InjectMocks
    StructuredOutputService structuredOutputService;

    private record TestAnalysis(String summary, java.util.List<String> risks) {}

    @Test
    void generate_parsesValidJsonIntoRecord() {
        when(promptManager.render(anyString(), anyMap())).thenReturn(new Prompt("test prompt"));
        when(chatModel.call(any(Prompt.class))).thenReturn(
            TestFixtures.chatResponse("""
                {"summary": "Flow is complex", "risks": ["coupling", "state"]}
                """));

        var result = structuredOutputService.generate(
            "flow-analysis", Map.of(), TestAnalysis.class);

        assertThat(result.summary()).isEqualTo("Flow is complex");
        assertThat(result.risks()).containsExactly("coupling", "state");
    }

    @Test
    void generate_addsFormatInstructionsToVariables() {
        when(promptManager.render(eq("flow-analysis"), ArgumentMatchers.argThat(vars ->
            vars.containsKey("format") && vars.get("format").toString().contains("properties")
        ))).thenReturn(new Prompt("with schema"));
        when(chatModel.call(any(Prompt.class))).thenReturn(
            TestFixtures.chatResponse("""
                {"summary": "ok", "risks": []}
                """));

        structuredOutputService.generate("flow-analysis", Map.of(), TestAnalysis.class);

        verify(promptManager).render(eq("flow-analysis"),
            ArgumentMatchers.argThat(vars -> vars.containsKey("format")));
    }

    @Test
    void generate_invalidJson_throwsConversionException() {
        when(promptManager.render(anyString(), anyMap())).thenReturn(new Prompt("test"));
        when(chatModel.call(any(Prompt.class))).thenReturn(
            TestFixtures.chatResponse("This is not JSON at all"));

        assertThatThrownBy(() ->
            structuredOutputService.generate("flow-analysis", Map.of(), TestAnalysis.class))
            .isInstanceOf(Exception.class);
    }
}
