package com.flowforge.embedding.health;

import com.flowforge.common.config.FlowForgeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeiHealthIndicatorTest {

    @Mock
    RestClient restClient;
    @Mock
    FlowForgeProperties props;

    TeiHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        when(props.tei()).thenReturn(new FlowForgeProperties.TeiProperties("http://tei-code:8081", "", ""));
        indicator = new TeiHealthIndicator(restClient, props);
    }

    @Test
    void health_teiReachable_returnsUpWithModelDetail() {
        RestClient.RequestHeadersUriSpec requestSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(requestSpec);
        when(requestSpec.uri("http://tei-code:8081/health")).thenReturn(requestSpec);
        when(requestSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn("{\"status\":\"ok\"}");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("model")).isEqualTo("codesage/codesage-large");
    }

    @Test
    void health_teiUnreachable_returnsDown() {
        when(restClient.get()).thenThrow(new RuntimeException("connection refused"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
