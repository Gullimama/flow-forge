package com.flowforge.vectorstore.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionOperationResponse;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points.UpdateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationArguments;

@ExtendWith(MockitoExtension.class)
class QdrantCollectionInitializerTest {

    @Mock QdrantClient client;
    @Mock EmbeddingModel codeEmbeddingModel;
    @Mock EmbeddingModel logEmbeddingModel;

    private static void with1024Dimensions(EmbeddingModel model) {
        when(model.dimensions()).thenReturn(1024);
    }

    @Test
    void run_existingCollection_skipsCreation() throws Exception {
        with1024Dimensions(codeEmbeddingModel);
        with1024Dimensions(logEmbeddingModel);
        when(client.getCollectionInfoAsync("code-embeddings"))
            .thenReturn(Futures.immediateFuture(CollectionInfo.getDefaultInstance()));
        when(client.getCollectionInfoAsync("log-embeddings"))
            .thenReturn(Futures.immediateFuture(CollectionInfo.getDefaultInstance()));
        when(client.getCollectionInfoAsync("config-embeddings"))
            .thenReturn(Futures.immediateFuture(CollectionInfo.getDefaultInstance()));

        var initializer = new QdrantCollectionInitializer(client, codeEmbeddingModel, logEmbeddingModel);
        initializer.run(Mockito.mock(ApplicationArguments.class));

        verify(client, never()).createCollectionAsync(anyString(), any(VectorParams.class));
    }

    @Test
    void run_missingCollection_createsWithCorrectDimension() throws Exception {
        with1024Dimensions(codeEmbeddingModel);
        with1024Dimensions(logEmbeddingModel);
        when(client.getCollectionInfoAsync("code-embeddings"))
            .thenReturn(Futures.immediateFailedFuture(new RuntimeException("not found")));
        when(client.createCollectionAsync(eq("code-embeddings"), any(VectorParams.class)))
            .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        when(client.createPayloadIndexAsync(anyString(), anyString(), eq(PayloadSchemaType.Keyword), any(), any(), any(), any()))
            .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
        when(client.getCollectionInfoAsync("log-embeddings"))
            .thenReturn(Futures.immediateFuture(CollectionInfo.getDefaultInstance()));
        when(client.getCollectionInfoAsync("config-embeddings"))
            .thenReturn(Futures.immediateFuture(CollectionInfo.getDefaultInstance()));

        var initializer = new QdrantCollectionInitializer(client, codeEmbeddingModel, logEmbeddingModel);
        initializer.run(Mockito.mock(ApplicationArguments.class));

        var captor = ArgumentCaptor.forClass(VectorParams.class);
        verify(client).createCollectionAsync(eq("code-embeddings"), captor.capture());
        assertThat(captor.getValue().getSize()).isEqualTo(1024);
        assertThat(captor.getValue().getDistance()).isEqualTo(io.qdrant.client.grpc.Collections.Distance.Cosine);
    }

    @Test
    void run_createsPayloadIndexesAfterCollectionCreation() throws Exception {
        with1024Dimensions(codeEmbeddingModel);
        with1024Dimensions(logEmbeddingModel);
        when(client.getCollectionInfoAsync(anyString()))
            .thenReturn(Futures.immediateFailedFuture(new RuntimeException("not found")));
        when(client.createCollectionAsync(anyString(), any(VectorParams.class)))
            .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        when(client.createPayloadIndexAsync(anyString(), anyString(), eq(PayloadSchemaType.Keyword), any(), any(), any(), any()))
            .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));

        var initializer = new QdrantCollectionInitializer(client, codeEmbeddingModel, logEmbeddingModel);
        initializer.run(Mockito.mock(ApplicationArguments.class));

        verify(client, atLeast(6)).createPayloadIndexAsync(
            anyString(), anyString(), eq(PayloadSchemaType.Keyword), any(), any(), any(), any());
    }

}
