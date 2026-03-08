package com.flowforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.api.dto.JobResponse;
import com.flowforge.api.dto.JobStatusResponse;
import com.flowforge.api.dto.SnapshotRequest;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@Tag("integration")
class FlowForgeApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("flowforge_test")
        .withUsername("flowforge")
        .withPassword("flowforge");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired private TestRestTemplate restTemplate;

    @Test
    @SuppressWarnings("unchecked")
    void healthEndpointReturnsUp() {
        var response = restTemplate.getForEntity("/actuator/health", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void snapshotEndToEndFlow() {
        var request = new SnapshotRequest("https://github.com/org/repo", "ghp_token");
        var response = restTemplate.postForEntity(
            "/api/v1/snapshots/master", request, JobResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().jobId()).isNotNull();

        var statusResponse = restTemplate.getForEntity(
            "/api/v1/jobs/{id}", JobStatusResponse.class, response.getBody().jobId());
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResponse.getBody()).isNotNull();
        assertThat(statusResponse.getBody().jobType()).isEqualTo("SNAPSHOT");
    }

    @Test
    void inputValidationReturns400() {
        var request = new SnapshotRequest("", null);
        var response = restTemplate.postForEntity(
            "/api/v1/snapshots/master", request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
