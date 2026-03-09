package com.flowforge.vectorstore.health;

import io.qdrant.client.QdrantClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class QdrantHealthIndicator implements HealthIndicator {

    private final QdrantClient client;

    public QdrantHealthIndicator(QdrantClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            List<String> collections = client.listCollectionsAsync().get();
            return Health.up()
                .withDetail("collections", collections != null ? collections.size() : 0)
                .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
