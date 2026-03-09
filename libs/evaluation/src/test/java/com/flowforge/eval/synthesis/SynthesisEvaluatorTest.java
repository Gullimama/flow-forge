package com.flowforge.eval.synthesis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.synthesis.model.FinalNarrativeOutput;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

@ExtendWith(MockitoExtension.class)
class SynthesisEvaluatorTest {

    @Mock
    org.springframework.ai.chat.model.ChatModel chatModel;

    @InjectMocks
    SynthesisEvaluator evaluator;

    @Test
    @DisplayName("callJudge strips markdown code fences from LLM response")
    void callJudge_stripsMarkdownFences() {
        var response = mockChatResponse("""
            ```json
            {"score": 0.85, "issues": ["minor gap in coverage"]}
            ```
            """);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        var flow = testFlow(List.of("ServiceA", "ServiceB"));
        var synthesis = testSynthesis("# Overview\nServiceA calls ServiceB therefore...");

        var eval = evaluator.evaluate(flow, synthesis, List.of());

        assertThat(eval.factualConsistency()).isCloseTo(0.85, within(0.01));
    }

    @Test
    @DisplayName("Completeness: all steps mentioned yields score 1.0")
    void completeness_allStepsCovered() {
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(mockChatResponse("{\"score\": 0.8, \"issues\": []}"));

        var flow = testFlow(List.of("OrderService", "PaymentService"));
        var synthesis = testSynthesis(
            "# Flow\nOrderService receives the request then PaymentService processes payment");

        var eval = evaluator.evaluate(flow, synthesis, List.of());
        assertThat(eval.completeness()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Completeness: missing steps reduces score below 0.8")
    void completeness_missingSteps() {
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(mockChatResponse("{\"score\": 0.5, \"issues\": []}"));

        var flow = testFlow(List.of("A", "B", "C", "D", "E"));
        var synthesis = testSynthesis("Only A and B are mentioned.");

        var eval = evaluator.evaluate(flow, synthesis, List.of());
        assertThat(eval.completeness()).isCloseTo(0.4, within(0.01));
        assertThat(eval.issues()).anyMatch(i -> i.contains("Missing coverage"));
    }

    @Test
    @DisplayName("callJudge returns 0.5 when LLM is unavailable")
    void callJudge_fallbackOnError() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));

        var eval = evaluator.evaluate(testFlow(List.of("Svc")),
            testSynthesis("narrative"), List.of());

        assertThat(eval.factualConsistency()).isEqualTo(0.5);
        assertThat(eval.issues()).anyMatch(i -> i.contains("LLM judge unavailable"));
    }

    @Test
    @DisplayName("Coherence: sections and logical connectors boost score")
    void coherence_goodStructure() {
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(mockChatResponse("{\"score\": 0.9, \"issues\": []}"));

        var narrative = """
            # Overview
            The system processes requests.
            ## Step 1
            Therefore, OrderService handles the order.
            ## Step 2
            As a result, PaymentService charges the card.
            ## Step 3
            Consequently, NotificationService sends confirmation.
            ## Step 4
            Finally, the flow completes.
            """;
        var eval = evaluator.evaluate(testFlow(List.of("OrderService")),
            testSynthesis(narrative), List.of());

        assertThat(eval.coherence()).isGreaterThan(0.5);
    }

    private ChatResponse mockChatResponse(String content) {
        var gen = new Generation(new AssistantMessage(content));
        return new ChatResponse(List.of(gen), null);
    }

    private FlowCandidate testFlow(List<String> services) {
        var steps = new java.util.ArrayList<com.flowforge.flow.model.FlowStep>();
        int order = 0;
        for (String name : services) {
            steps.add(new com.flowforge.flow.model.FlowStep(
                order++,
                name,
                "action",
                com.flowforge.flow.model.FlowStep.StepType.HTTP_ENDPOINT,
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                List.of(),
                null,
                java.util.Optional.empty()
            ));
        }
        return new FlowCandidate(
            UUID.randomUUID(), UUID.randomUUID(), "flow-1",
            FlowCandidate.FlowType.SYNC_REQUEST, steps, services, null, 0.9,
            FlowCandidate.FlowComplexity.MEDIUM
        );
    }

    private FinalNarrativeOutput testSynthesis(String narrative) {
        return new FinalNarrativeOutput(
            "flow-1", "summary", narrative,
            List.of(), List.of(), List.of(), "next");
    }
}

