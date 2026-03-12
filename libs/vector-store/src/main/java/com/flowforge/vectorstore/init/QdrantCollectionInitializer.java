package com.flowforge.vectorstore.init;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class QdrantCollectionInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QdrantCollectionInitializer.class);
    private static final int DEFAULT_CONFIG_DIMENSION = 1024;

    private final QdrantClient client;
    private final EmbeddingModel codeEmbeddingModel;
    private final EmbeddingModel logEmbeddingModel;

    public QdrantCollectionInitializer(QdrantClient client,
                                       @Qualifier("codeEmbeddingModel") EmbeddingModel codeEmbeddingModel,
                                       @Qualifier("logEmbeddingModel") EmbeddingModel logEmbeddingModel) {
        this.client = client;
        this.codeEmbeddingModel = codeEmbeddingModel;
        this.logEmbeddingModel = logEmbeddingModel;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int codeDim = codeEmbeddingModel.dimensions();
        int logDim = logEmbeddingModel.dimensions();
        ensureCollection("code-embeddings", codeDim, Distance.Cosine);
        ensureCollection("log-embeddings", logDim, Distance.Cosine);
        ensureCollection("config-embeddings", Math.max(codeDim, Math.max(logDim, DEFAULT_CONFIG_DIMENSION)), Distance.Cosine);
    }

    private void ensureCollection(String name, int dimension, Distance distance) {
        try {
            client.getCollectionInfoAsync(name).get(10, TimeUnit.SECONDS);
            log.info("Collection '{}' already exists", name);
        } catch (Exception e) {
            try {
                client.createCollectionAsync(name,
                    VectorParams.newBuilder()
                        .setSize(dimension)
                        .setDistance(distance)
                        .build()
                ).get(30, TimeUnit.SECONDS);
                log.info("Created collection '{}'", name);
                client.createPayloadIndexAsync(name, "snapshot_id",
                    PayloadSchemaType.Keyword, null, null, null, null).get();
                client.createPayloadIndexAsync(name, "service_name",
                    PayloadSchemaType.Keyword, null, null, null, null).get();
            } catch (Exception ex) {
                throw new RuntimeException("Failed to create collection " + name, ex);
            }
        }
    }
}
