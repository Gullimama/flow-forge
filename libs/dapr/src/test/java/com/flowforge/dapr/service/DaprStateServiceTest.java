package com.flowforge.dapr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.flowforge.dapr.config.DaprProperties;
import io.dapr.client.DaprClient;
import io.dapr.client.domain.State;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class DaprStateServiceTest {

    @Mock
    DaprClient daprClient;

    @Test
    @DisplayName("getState wraps Dapr State in Optional")
    void getState_optional() {
        var props = new DaprProperties("localhost", 3500, 50001,
            "pubsub", "state-store", "secrets", Duration.ofSeconds(5));
        var service = new DaprStateService(daprClient, props);

        when(daprClient.getState(eq("state-store"), eq("key"), eq(String.class)))
            .thenReturn(Mono.just(new State<>("key", "value", "")));

        Optional<String> result = service.getState("key", String.class);

        assertThat(result).contains("value");
    }
}

