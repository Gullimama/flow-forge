package com.flowforge.mlflow;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.mlflow.client.MlflowClient;
import com.flowforge.mlflow.config.MlflowProperties;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

@WireMockTest
class MlflowClientTest {

    private MlflowClient client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        var props = new MlflowProperties(
            wmRuntimeInfo.getHttpBaseUrl(), "test-experiment", "s3://artifacts",
            Duration.ofSeconds(5), 3);
        client = new MlflowClient(props, RestClient.builder());
    }

    @Test
    @DisplayName("getOrCreateExperiment returns existing experiment ID")
    void getOrCreateExperiment_existing() {
        stubFor(get(urlPathEqualTo("/api/2.0/mlflow/experiments/get-by-name"))
            .withQueryParam("experiment_name", equalTo("test-experiment"))
            .willReturn(okJson("""
                {"experiment": {"experiment_id": "42", "name": "test-experiment"}}
                """)));

        var id = client.getOrCreateExperiment("test-experiment");
        assertThat(id).isEqualTo("42");
    }

    @Test
    @DisplayName("getOrCreateExperiment creates new when 404")
    void getOrCreateExperiment_creates() {
        stubFor(get(urlPathEqualTo("/api/2.0/mlflow/experiments/get-by-name"))
            .willReturn(notFound()));
        stubFor(post(urlPathEqualTo("/api/2.0/mlflow/experiments/create"))
            .willReturn(okJson("""
                {"experiment_id": "99"}
                """)));

        var id = client.getOrCreateExperiment("new-experiment");
        assertThat(id).isEqualTo("99");
        verify(postRequestedFor(urlPathEqualTo("/api/2.0/mlflow/experiments/create"))
            .withRequestBody(matchingJsonPath("$.name", equalTo("new-experiment"))));
    }

    @Test
    @DisplayName("createRun sends tags and returns run info")
    void createRun_withTags() {
        stubFor(post(urlPathEqualTo("/api/2.0/mlflow/runs/create"))
            .willReturn(okJson("""
                {"run": {"info": {"run_id": "run-1", "experiment_id": "42",
                 "status": "RUNNING", "start_time": 1700000000000},
                 "data": {"metrics": [], "params": []}}}
                """)));

        var run = client.createRun("42", "test-run", Map.of("key", "val"));
        assertThat(run.runId()).isEqualTo("run-1");
    }

    @Test
    @DisplayName("logParam sends correct payload")
    void logParam_sendsPayload() {
        stubFor(post(urlPathEqualTo("/api/2.0/mlflow/runs/log-parameter"))
            .willReturn(ok()));

        client.logParam("run-1", "learning_rate", "0.01");

        verify(postRequestedFor(urlPathEqualTo("/api/2.0/mlflow/runs/log-parameter"))
            .withRequestBody(matchingJsonPath("$.run_id", equalTo("run-1")))
            .withRequestBody(matchingJsonPath("$.key", equalTo("learning_rate")))
            .withRequestBody(matchingJsonPath("$.value", equalTo("0.01"))));
    }

    @Test
    @DisplayName("logMetric includes timestamp and step")
    void logMetric_includesTimestampAndStep() {
        stubFor(post(urlPathEqualTo("/api/2.0/mlflow/runs/log-metric"))
            .willReturn(ok()));

        client.logMetric("run-1", "accuracy", 0.95, 10);

        verify(postRequestedFor(urlPathEqualTo("/api/2.0/mlflow/runs/log-metric"))
            .withRequestBody(matchingJsonPath("$.value", equalTo("0.95")))
            .withRequestBody(matchingJsonPath("$.step", equalTo("10"))));
    }

    @Test
    @DisplayName("logArtifact uses PUT with octet-stream content type")
    void logArtifact_putRequest() {
        stubFor(put(urlPathMatching("/api/2.0/mlflow-artifacts/artifacts/.*"))
            .willReturn(ok()));

        client.logArtifact("run-1", "model.bin", new byte[]{1, 2, 3});

        verify(putRequestedFor(urlPathMatching("/api/2.0/mlflow-artifacts/artifacts/model.bin.*"))
            .withHeader("Content-Type", equalTo("application/octet-stream")));
    }

    @Test
    @DisplayName("endRun sends FINISHED status")
    void endRun_finished() {
        stubFor(post(urlPathEqualTo("/api/2.0/mlflow/runs/update"))
            .willReturn(ok()));

        client.endRun("run-1", "FINISHED");

        verify(postRequestedFor(urlPathEqualTo("/api/2.0/mlflow/runs/update"))
            .withRequestBody(matchingJsonPath("$.status", equalTo("FINISHED"))));
    }
}

