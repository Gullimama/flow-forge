package com.flowforge.embedding.config;

import com.flowforge.common.config.FlowForgeProperties;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "flowforge.embedding.provider", havingValue = "tei")
public class TeiEmbeddingConfig {

    /**
     * Code embedding model — connects to TEI serving CodeSage-large.
     * TEI exposes an OpenAI-compatible /v1/embeddings endpoint.
     */
    @Bean("codeEmbeddingModel")
    public EmbeddingModel codeEmbeddingModel(FlowForgeProperties props) {
        var options = OpenAiEmbeddingOptions.builder()
            .model("codesage/codesage-large")
            .dimensions(1024)
            .build();

        var api = OpenAiApi.builder()
            .baseUrl(props.tei().codeUrl())
            .apiKey("not-needed")
            .build();

        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }

    /**
     * Log embedding model — connects to TEI serving E5-large-v2.
     */
    @Bean("logEmbeddingModel")
    public EmbeddingModel logEmbeddingModel(FlowForgeProperties props) {
        var options = OpenAiEmbeddingOptions.builder()
            .model("intfloat/e5-large-v2")
            .dimensions(1024)
            .build();

        var api = OpenAiApi.builder()
            .baseUrl(props.tei().logUrl())
            .apiKey("not-needed")
            .build();

        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }
}
