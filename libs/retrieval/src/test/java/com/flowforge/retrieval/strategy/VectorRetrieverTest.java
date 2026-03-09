package com.flowforge.retrieval.strategy;

import com.flowforge.retrieval.model.RankedDocument;
import com.flowforge.retrieval.RetrievalTestFixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import com.flowforge.vectorstore.service.VectorStoreService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorRetrieverTest {

    @Mock
    VectorStoreService vectorStoreService;
    @InjectMocks
    VectorRetriever vectorRetriever;

    @Test
    void retrieveLogs_prependsQueryPrefixForE5() {
        when(vectorStoreService.searchLogs(any(), anyInt(), any(), any()))
            .thenReturn(List.of());

        vectorRetriever.retrieveLogs(RetrievalTestFixtures.retrievalRequest("connection failure"));

        verify(vectorStoreService).searchLogs(
            argThat((String q) -> q.startsWith("query: ")), anyInt(), any(), any());
    }

    @Test
    void retrieveCode_mapsDocumentsToRankedDocumentWithVectorCodeSource() {
        var doc = new Document("class Foo {}", java.util.Map.of("distance", 0.85));
        when(vectorStoreService.searchCode(any(), anyInt(), any()))
            .thenReturn(List.of(doc));

        var results = vectorRetriever.retrieveCode(RetrievalTestFixtures.retrievalRequest("Foo"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).source()).isEqualTo(RankedDocument.DocumentSource.VECTOR_CODE);
        assertThat(results.get(0).score()).isEqualTo(0.85);
    }

    @Test
    void retrieveCode_logsScope_returnsEmpty() {
        var request = new com.flowforge.retrieval.model.RetrievalRequest(
            RetrievalTestFixtures.SNAPSHOT_ID, "q", com.flowforge.retrieval.model.RetrievalRequest.RetrievalScope.LOGS,
            5, Optional.empty(), Optional.empty());
        var results = vectorRetriever.retrieveCode(request);
        assertThat(results).isEmpty();
        verify(vectorStoreService, org.mockito.Mockito.never()).searchCode(any(), anyInt(), any());
    }
}
