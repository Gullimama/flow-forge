package com.flowforge.vectorstore.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.util.List;

@ExtendWith(MockitoExtension.class)
class QdrantHealthIndicatorTest {

    @Mock QdrantClient client;
    QdrantHealthIndicator indicator;

    @Test
    void health_qdrantReachable_returnsUp() throws Exception {
        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of("code-embeddings", "log-embeddings")));
        indicator = new QdrantHealthIndicator(client);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("collections")).isEqualTo(2);
    }

    @Test
    void health_qdrantDown_returnsDown() {
        when(client.listCollectionsAsync()).thenReturn(Futures.immediateFailedFuture(new RuntimeException("connection refused")));
        indicator = new QdrantHealthIndicator(client);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
