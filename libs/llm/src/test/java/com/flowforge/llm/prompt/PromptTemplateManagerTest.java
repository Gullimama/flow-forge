package com.flowforge.llm.prompt;

import com.flowforge.llm.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplateManagerTest {

    private PromptTemplateManager manager;

    @BeforeEach
    void setUp() {
        manager = new PromptTemplateManager();
        manager.loadTemplates();
    }

    @Test
    void render_knownTemplate_populatesVariables() {
        var prompt = manager.render("flow-analysis", Map.of(
            "flowName", "booking-creation-flow",
            "flowType", "SYNC_REQUEST",
            "services", "api-gateway, booking-service",
            "codeEvidence", "class BookingController { ... }",
            "logPatterns", "ERROR: Connection refused",
            "graphContext", "api-gateway -> booking-service",
            "format", "{}"
        ));
        var text = prompt.getContents();
        assertThat(text).contains("booking-creation-flow");
        assertThat(text).contains("SYNC_REQUEST");
        assertThat(text).contains("api-gateway, booking-service");
    }

    @Test
    void render_unknownTemplate_throwsIllegalArgument() {
        assertThatThrownBy(() -> manager.render("nonexistent", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown template: nonexistent");
    }

    @Test
    void allElevenTemplates_loadSuccessfully() {
        var templateNames = List.of(
            "flow-analysis", "code-explanation", "migration-risk",
            "dependency-analysis", "reactive-complexity",
            "synthesis-stage1", "synthesis-stage2", "synthesis-stage3",
            "synthesis-stage4", "synthesis-stage5", "synthesis-stage6"
        );
        for (var name : templateNames) {
            assertThatCode(() -> manager.render(name, TestFixtures.minimalTemplateVars(name)))
                .doesNotThrowAnyException();
        }
    }
}
