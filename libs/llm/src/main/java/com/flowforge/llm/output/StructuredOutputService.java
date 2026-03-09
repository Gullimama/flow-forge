package com.flowforge.llm.output;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import com.flowforge.llm.prompt.PromptTemplateManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates structured LLM output by rendering a prompt template, calling the ChatModel,
 * and parsing the response with BeanOutputConverter into a Java type.
 */
@Component
public class StructuredOutputService {

    private final ChatModel chatModel;
    private final PromptTemplateManager promptManager;

    public StructuredOutputService(ChatModel chatModel, PromptTemplateManager promptManager) {
        this.chatModel = chatModel;
        this.promptManager = promptManager;
    }

    /**
     * Generate structured output parsed into a Java record/class.
     * BeanOutputConverter appends JSON schema instructions to the prompt and parses the response.
     */
    public <T> T generate(String templateName, Map<String, Object> variables, Class<T> outputType) {
        var converter = new BeanOutputConverter<>(outputType);
        var augmentedVars = new HashMap<>(variables);
        augmentedVars.put("format", converter.getFormat());

        var prompt = promptManager.render(templateName, augmentedVars);
        var response = chatModel.call(prompt);

        String content = getResponseContent(response);
        return converter.convert(content);
    }

    /**
     * Generate with a list output type.
     */
    public <T> List<T> generateList(String templateName, Map<String, Object> variables,
                                    ParameterizedTypeReference<List<T>> outputType) {
        var converter = new BeanOutputConverter<>(outputType);
        var augmentedVars = new HashMap<>(variables);
        augmentedVars.put("format", converter.getFormat());

        var prompt = promptManager.render(templateName, augmentedVars);
        var response = chatModel.call(prompt);

        String content = getResponseContent(response);
        return converter.convert(content);
    }

    private static String getResponseContent(org.springframework.ai.chat.model.ChatResponse response) {
        Generation result = response.getResult();
        if (result == null || result.getOutput() == null) {
            return "";
        }
        return result.getOutput().getText();
    }
}
