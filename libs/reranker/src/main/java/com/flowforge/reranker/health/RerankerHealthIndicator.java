package com.flowforge.reranker.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RerankerHealthIndicator implements HealthIndicator {

    private static final String MODEL = "BAAI/bge-reranker-v2-m3";

    private final RestClient restClient;

    public RerankerHealthIndicator(
            @org.springframework.beans.factory.annotation.Qualifier("rerankerRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Health health() {
        try {
            restClient.get()
                .uri("/health")
                .retrieve()
                .body(String.class);
            return Health.up()
                .withDetail("model", MODEL)
                .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
