package com.flowforge.vectorstore.config;

import com.flowforge.common.config.FlowForgeProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;

@Configuration
public class QdrantConfig {

    @Bean
    public QdrantClient qdrantClient(FlowForgeProperties props) {
        return new QdrantClient(
            QdrantGrpcClient.newBuilder(
                props.qdrant().host(),
                props.qdrant().port(),
                false
            ).build()
        );
    }

    @Bean("codeEmbeddingModel")
    @ConditionalOnMissingBean(name = "codeEmbeddingModel")
    public EmbeddingModel codeEmbeddingModel() {
        return new StubEmbeddingModel();
    }

    @Bean("logEmbeddingModel")
    @ConditionalOnMissingBean(name = "logEmbeddingModel")
    public EmbeddingModel logEmbeddingModel() {
        return new StubEmbeddingModel();
    }

    @Bean("codeVectorStore")
    public VectorStore codeVectorStore(QdrantClient client,
                                       @Qualifier("codeEmbeddingModel") EmbeddingModel embeddingModel) {
        return QdrantVectorStore.builder(client, embeddingModel)
            .collectionName("code-embeddings")
            .initializeSchema(true)
            .build();
    }

    @Bean("logVectorStore")
    public VectorStore logVectorStore(QdrantClient client,
                                      @Qualifier("logEmbeddingModel") EmbeddingModel embeddingModel) {
        return QdrantVectorStore.builder(client, embeddingModel)
            .collectionName("log-embeddings")
            .initializeSchema(true)
            .build();
    }
}
