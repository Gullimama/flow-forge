package com.flowforge.common.config;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.health.MinioHealthIndicator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "flowforge.minio", name = "endpoint")
@EnableConfigurationProperties(FlowForgeProperties.class)
public class MinioClientConfiguration {

    @Bean
    public MinioClient minioClient(FlowForgeProperties props) {
        return MinioClient.builder()
            .endpoint(props.minio().endpoint())
            .credentials(props.minio().accessKey(), props.minio().secretKey())
            .build();
    }

    @Bean
    public MinioStorageClient minioStorageClient(MinioClient minioClient, ObjectMapper objectMapper) {
        return new MinioStorageClient(minioClient, objectMapper);
    }

    @Bean
    public MinioHealthIndicator minioHealthIndicator(MinioStorageClient storageClient) {
        return new MinioHealthIndicator(storageClient);
    }
}
