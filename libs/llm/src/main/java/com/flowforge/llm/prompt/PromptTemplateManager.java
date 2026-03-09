package com.flowforge.llm.prompt;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches prompt templates from classpath; renders them with variables into Spring AI Prompts.
 */
@Component
public class PromptTemplateManager {

    private final Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadTemplates() {
        templates.put("flow-analysis", loadTemplate("prompts/flow-analysis.st"));
        templates.put("code-explanation", loadTemplate("prompts/code-explanation.st"));
        templates.put("migration-risk", loadTemplate("prompts/migration-risk.st"));
        templates.put("dependency-analysis", loadTemplate("prompts/dependency-analysis.st"));
        templates.put("reactive-complexity", loadTemplate("prompts/reactive-complexity.st"));
        templates.put("synthesis-stage1", loadTemplate("prompts/synthesis-stage1.st"));
        templates.put("synthesis-stage2", loadTemplate("prompts/synthesis-stage2.st"));
        templates.put("synthesis-stage3", loadTemplate("prompts/synthesis-stage3.st"));
        templates.put("synthesis-stage4", loadTemplate("prompts/synthesis-stage4.st"));
        templates.put("synthesis-stage5", loadTemplate("prompts/synthesis-stage5.st"));
        templates.put("synthesis-stage6", loadTemplate("prompts/synthesis-stage6.st"));
    }

    /**
     * Render a named template with the given variables.
     */
    public Prompt render(String templateName, Map<String, Object> variables) {
        var template = templates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Unknown template: " + templateName);
        }
        return template.create(variables);
    }

    private static PromptTemplate loadTemplate(String resourcePath) {
        return new PromptTemplate(new ClassPathResource(resourcePath));
    }
}
