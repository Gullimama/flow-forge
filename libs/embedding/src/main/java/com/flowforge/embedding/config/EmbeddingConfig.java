package com.flowforge.embedding.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

@Configuration
@Import({ TeiEmbeddingConfig.class, OllamaEmbeddingConfig.class })
public class EmbeddingConfig {

    @Bean
    @ConditionalOnMissingBean(RestClient.class)
    public RestClient restClient() {
        return RestClient.builder().build();
    }
}
