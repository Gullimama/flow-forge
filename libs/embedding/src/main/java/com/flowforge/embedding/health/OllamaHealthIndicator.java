package com.flowforge.embedding.health;

import com.flowforge.common.config.FlowForgeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "flowforge.embedding.provider", havingValue = "ollama")
public class OllamaHealthIndicator implements HealthIndicator {

    private final RestClient restClient;
    private final FlowForgeProperties props;

    public OllamaHealthIndicator(RestClient restClient, FlowForgeProperties props) {
        this.restClient = restClient;
        this.props = props;
    }

    @Override
    public Health health() {
        String baseUrl = props.ollama() != null && props.ollama().baseUrl() != null && !props.ollama().baseUrl().isBlank()
            ? props.ollama().baseUrl()
            : "http://localhost:11434";
        String model = props.ollama() != null && props.ollama().embeddingModel() != null && !props.ollama().embeddingModel().isBlank()
            ? props.ollama().embeddingModel()
            : "nomic-embed-text";

        try {
            restClient.get()
                .uri(baseUrl + "/api/tags")
                .retrieve()
                .body(String.class);
            return Health.up()
                .withDetail("embeddingModel", model)
                .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
