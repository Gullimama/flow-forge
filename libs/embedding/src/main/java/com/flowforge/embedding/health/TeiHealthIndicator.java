package com.flowforge.embedding.health;

import com.flowforge.common.config.FlowForgeProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TeiHealthIndicator implements HealthIndicator {

    private final RestClient restClient;
    private final FlowForgeProperties props;

    public TeiHealthIndicator(RestClient restClient, FlowForgeProperties props) {
        this.restClient = restClient;
        this.props = props;
    }

    @Override
    public Health health() {
        try {
            String url = props.tei().codeUrl() + "/health";
            restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);
            return Health.up()
                .withDetail("model", "codesage/codesage-large")
                .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
