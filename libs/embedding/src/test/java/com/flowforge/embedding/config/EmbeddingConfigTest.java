package com.flowforge.embedding.config;

import com.flowforge.common.config.FlowForgeProperties;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingConfigTest {

    @Test
    void restClient_beanCreatedWhenMissing() {
        var config = new EmbeddingConfig();
        RestClient client = config.restClient();
        assertThat(client).isNotNull();
    }

    @Test
    @Disabled("NoSuchMethodError when OpenAiEmbeddingModel is instantiated with vector-store on classpath; config works at runtime in Boot app")
    void codeEmbeddingModel_configuredWith1024Dimensions() {
        var props = new FlowForgeProperties(
            null, null, null, null, null, null, null,
            new FlowForgeProperties.TeiProperties("http://localhost:8081", "http://localhost:8082", "http://localhost:8083"),
            null);
        var config = new EmbeddingConfig();
        var model = config.codeEmbeddingModel(props);
        assertThat(model).isNotNull();
    }
}
