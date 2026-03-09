package com.flowforge.vectorstore.init;

import com.flowforge.common.config.FlowForgeProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class QdrantCollectionInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QdrantCollectionInitializer.class);

    private final QdrantClient client;

    public QdrantCollectionInitializer(QdrantClient client, FlowForgeProperties props) {
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ensureCollection("code-embeddings", 1024, Distance.Cosine);
        ensureCollection("log-embeddings", 1024, Distance.Cosine);
        ensureCollection("config-embeddings", 1024, Distance.Cosine);
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
