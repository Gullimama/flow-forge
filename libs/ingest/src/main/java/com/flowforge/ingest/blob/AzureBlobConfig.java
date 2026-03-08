package com.flowforge.ingest.blob;

import com.flowforge.common.config.FlowForgeProperties;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "flowforge.azure", name = "enabled", havingValue = "true", matchIfMissing = false)
public class AzureBlobConfig {

    @Bean
    public BlobServiceClient blobServiceClient(FlowForgeProperties props) {
        if (props.azure() == null || props.azure().connectionString() == null || props.azure().connectionString().isBlank()) {
            throw new IllegalStateException("flowforge.azure.connection-string is required when flowforge.azure.enabled=true");
        }
        return new BlobServiceClientBuilder()
            .connectionString(props.azure().connectionString())
            .buildClient();
    }
}
