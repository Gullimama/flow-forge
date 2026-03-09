package com.flowforge.mlflow;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.mlflow.config.MlflowProperties;
import com.flowforge.mlflow.health.MlflowHealthIndicator;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.client.RestClient;

@WireMockTest
class MlflowHealthIndicatorTest {

    private MlflowHealthIndicator indicator;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        var props = new MlflowProperties(
            wmRuntimeInfo.getHttpBaseUrl(), "test", null, Duration.ofSeconds(5), 3);
        indicator = new MlflowHealthIndicator(props, RestClient.builder());
    }

    @Test
    @DisplayName("Reports UP when MLflow search endpoint responds")
    void healthUp() {
        stubFor(get(urlPathEqualTo("/api/2.0/mlflow/experiments/search"))
            .willReturn(ok()));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("Reports DOWN when MLflow is unreachable")
    void healthDown() {
        stubFor(get(urlPathEqualTo("/api/2.0/mlflow/experiments/search"))
            .willReturn(serverError()));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}

