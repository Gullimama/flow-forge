package com.flowforge.reranker.config;

import com.flowforge.common.config.FlowForgeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RerankerConfig {

    @Bean("rerankerRestClient")
    public RestClient rerankerRestClient(FlowForgeProperties props) {
        String baseUrl = props.tei() != null && props.tei().rerankerUrl() != null && !props.tei().rerankerUrl().isBlank()
            ? props.tei().rerankerUrl()
            : "http://localhost:8083";
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
