package com.flowforge.dapr.config;

import io.dapr.client.DaprClient;
import io.dapr.client.DaprClientBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DaprProperties.class)
public class DaprClientConfig {

    @Bean
    public DaprClient daprClient(DaprProperties props) {
        System.setProperty("dapr.sidecar.ip", props.sidecarHost());
        System.setProperty("dapr.http.port", String.valueOf(props.sidecarHttpPort()));
        System.setProperty("dapr.grpc.port", String.valueOf(props.sidecarGrpcPort()));
        return new DaprClientBuilder().build();
    }

    @Bean
    public io.dapr.client.DaprPreviewClient daprPreviewClient(DaprProperties props) {
        System.setProperty("dapr.sidecar.ip", props.sidecarHost());
        System.setProperty("dapr.http.port", String.valueOf(props.sidecarHttpPort()));
        System.setProperty("dapr.grpc.port", String.valueOf(props.sidecarGrpcPort()));
        return new DaprClientBuilder().buildPreviewClient();
    }
}

