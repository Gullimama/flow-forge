package com.flowforge.vectorstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.qdrant.client.grpc.Points.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VectorStoreServiceTest {

    @Mock VectorStore codeVectorStore;
    @Mock VectorStore logVectorStore;
    @Mock io.qdrant.client.QdrantClient qdrantClient;
    @Mock MeterRegistry meterRegistry;
    @Mock Timer timer;

    VectorStoreService vectorStoreService;

    @BeforeEach
    void setUp() {
        when(meterRegistry.timer(anyString())).thenReturn(timer);
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; })
            .when(timer).record(any(Runnable.class));
        when(timer.record(any(Supplier.class))).thenAnswer(inv ->
            inv.getArgument(0, Supplier.class).get());
        vectorStoreService = new VectorStoreService(
            codeVectorStore, logVectorStore, qdrantClient, meterRegistry);
    }

    @Test
    void addCodeDocuments_delegatesToCodeVectorStore() {
        var docs = List.of(new Document("test content", java.util.Map.of("snapshot_id", "snap-1")));
        vectorStoreService.addCodeDocuments(docs);
        verify(codeVectorStore).add(docs);
    }

    @Test
    void addLogDocuments_delegatesToLogVectorStore() {
        var docs = List.of(new Document("log content", java.util.Map.of("snapshot_id", "snap-1")));
        vectorStoreService.addLogDocuments(docs);
        verify(logVectorStore).add(docs);
    }

    @Test
    void searchCode_buildsFilterExpressionWithSnapshotId() {
        when(codeVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        vectorStoreService.searchCode("booking endpoint", 10, "snap-123");
        var captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(codeVectorStore).similaritySearch(captor.capture());
        var request = captor.getValue();
        assertThat(request.getQuery()).isEqualTo("booking endpoint");
        assertThat(request.getTopK()).isEqualTo(10);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.5);
    }

    @Test
    void searchLogs_withServiceName_addsServiceFilter() {
        when(logVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        vectorStoreService.searchLogs("timeout error", 5, "snap-1", Optional.of("order-service"));
        var captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(logVectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getFilterExpression()).isNotNull();
    }

    @Test
    void searchLogs_withoutServiceName_onlySnapshotFilter() {
        when(logVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        vectorStoreService.searchLogs("timeout error", 5, "snap-1", Optional.empty());
        var captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(logVectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getFilterExpression()).isNotNull();
    }

    @Test
    void deleteBySnapshot_deletesFromBothCollections() throws Exception {
        var future = com.google.common.util.concurrent.Futures.immediateFuture(
            io.qdrant.client.grpc.Points.UpdateResult.getDefaultInstance());
        when(qdrantClient.deleteAsync(anyString(), any(Filter.class))).thenReturn(future);
        vectorStoreService.deleteBySnapshot("snap-123");
        var captor = ArgumentCaptor.forClass(Filter.class);
        verify(qdrantClient).deleteAsync(eq("code-embeddings"), captor.capture());
        verify(qdrantClient).deleteAsync(eq("log-embeddings"), captor.capture());
    }
}
