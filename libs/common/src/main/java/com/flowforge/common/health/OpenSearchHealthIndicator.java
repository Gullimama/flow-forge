package com.flowforge.common.health;

import com.flowforge.common.client.OpenSearchClientWrapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Spring Boot Actuator health indicator for OpenSearch.
 */
@Component
@ConditionalOnBean(OpenSearchClientWrapper.class)
public class OpenSearchHealthIndicator implements HealthIndicator {

    private final OpenSearchClientWrapper client;

    public OpenSearchHealthIndicator(OpenSearchClientWrapper client) {
        this.client = client;
    }

    @Override
    public Health health() {
        return client.healthCheck()
            ? Health.up().withDetail("opensearch", "reachable").build()
            : Health.down().withDetail("opensearch", "unreachable or red").build();
    }
}
