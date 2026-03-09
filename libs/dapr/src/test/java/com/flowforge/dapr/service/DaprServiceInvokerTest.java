package com.flowforge.dapr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.dapr.client.DaprClient;
import io.dapr.client.domain.HttpExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class DaprServiceInvokerTest {

    @Mock
    DaprClient daprClient;

    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    DaprServiceInvoker invoker;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        invoker = new DaprServiceInvoker(daprClient, new com.fasterxml.jackson.databind.ObjectMapper(), meterRegistry);
    }

    @Test
    @DisplayName("invoke returns response on success")
    void invoke_success() {
        when(daprClient.invokeMethod(eq("svc"), eq("method"), any(), eq(HttpExtension.POST), eq(String.class)))
            .thenReturn(Mono.just("ok"));

        String result = invoker.invoke("svc", "method", "req", String.class);

        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("invoke throws DaprInvocationException on error")
    void invoke_error() {
        when(daprClient.invokeMethod(eq("svc"), eq("method"), any(), eq(HttpExtension.POST), eq(String.class)))
            .thenReturn(Mono.error(new RuntimeException("boom")));

        assertThatThrownBy(() ->
            invoker.invoke("svc", "method", "req", String.class)
        ).isInstanceOf(DaprInvocationException.class);
    }
}

