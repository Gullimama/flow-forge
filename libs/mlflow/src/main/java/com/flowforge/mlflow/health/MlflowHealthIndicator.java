package com.flowforge.mlflow.health;

import com.flowforge.mlflow.config.MlflowProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class MlflowHealthIndicator implements HealthIndicator {

    private final RestClient restClient;
    private final MlflowProperties props;

    public MlflowHealthIndicator(MlflowProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder.baseUrl(props.trackingUri()).build();
    }

    @Override
    public Health health() {
        try {
            restClient.get()
                .uri("/api/2.0/mlflow/experiments/search?max_results=1")
                .retrieve()
                .toBodilessEntity();
            return Health.up()
                .withDetail("trackingUri", props.trackingUri())
                .build();
        } catch (Exception e) {
            return Health.down(e)
                .withDetail("trackingUri", props.trackingUri())
                .build();
        }
    }
}

