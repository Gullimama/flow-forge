package com.flowforge.embedding.config;

import com.flowforge.common.config.FlowForgeProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "flowforge.embedding.provider", havingValue = "ollama")
public class OllamaEmbeddingConfig {

    /**
     * Code embedding model — uses Ollama (e.g. nomic-embed-text) for local macOS.
     * Same model used for both code and log when provider is ollama.
     */
    @Bean("codeEmbeddingModel")
    public EmbeddingModel codeEmbeddingModel(FlowForgeProperties props, ObservationRegistry observationRegistry) {
        return createOllamaEmbeddingModel(props, observationRegistry);
    }

    /**
     * Log embedding model — same Ollama model as code when provider is ollama.
     */
    @Bean("logEmbeddingModel")
    public EmbeddingModel logEmbeddingModel(FlowForgeProperties props, ObservationRegistry observationRegistry) {
        return createOllamaEmbeddingModel(props, observationRegistry);
    }

    private static EmbeddingModel createOllamaEmbeddingModel(FlowForgeProperties props, ObservationRegistry observationRegistry) {
        var ollama = props.ollama() != null ? props.ollama() : null;
        String baseUrl = ollama != null && ollama.baseUrl() != null && !ollama.baseUrl().isBlank()
            ? ollama.baseUrl()
            : "http://localhost:11434";
        String model = ollama != null && ollama.embeddingModel() != null && !ollama.embeddingModel().isBlank()
            ? ollama.embeddingModel()
            : "nomic-embed-text";

        var api = OllamaApi.builder()
            .baseUrl(baseUrl)
            .build();

        var options = OllamaOptions.builder()
            .model(model)
            .build();

        return new OllamaEmbeddingModel(api, options, observationRegistry, ModelManagementOptions.defaults());
    }
}
