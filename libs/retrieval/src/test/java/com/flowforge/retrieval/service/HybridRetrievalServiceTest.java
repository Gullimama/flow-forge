package com.flowforge.retrieval.service;

import com.flowforge.retrieval.model.RankedDocument;
import com.flowforge.retrieval.model.RetrievalRequest;
import com.flowforge.retrieval.RetrievalTestFixtures;
import com.flowforge.retrieval.fusion.ReciprocalRankFusion;
import com.flowforge.retrieval.strategy.BM25Retriever;
import com.flowforge.retrieval.strategy.GraphRetriever;
import com.flowforge.retrieval.strategy.VectorRetriever;
import com.flowforge.reranker.resilient.ResilientReranker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HybridRetrievalServiceTest {

    @Mock VectorRetriever vectorRetriever;
    @Mock BM25Retriever bm25Retriever;
    @Mock GraphRetriever graphRetriever;
    @Mock ReciprocalRankFusion rrfFusion;
    @Mock ResilientReranker reranker;
    @Mock MeterRegistry meterRegistry;
    @Mock Timer timer;

    @InjectMocks HybridRetrievalService service;

    @Test
    void retrieve_codeScope_doesNotQueryLogRetrievers() {
        var request = RetrievalTestFixtures.retrievalRequest("booking",
            RetrievalRequest.RetrievalScope.CODE);
        when(vectorRetriever.retrieveCode(any())).thenReturn(List.of());
        when(bm25Retriever.retrieveCode(any())).thenReturn(List.of());
        when(graphRetriever.retrieve(any())).thenReturn(List.of());
        when(rrfFusion.fuse(anyList())).thenReturn(List.of());
        when(reranker.rerank(any(), anyList(), anyInt())).thenReturn(List.of());
        when(meterRegistry.timer(any())).thenReturn(timer);

        service.retrieve(request);

        verify(vectorRetriever, never()).retrieveLogs(any());
        verify(bm25Retriever, never()).retrieveLogs(any());
    }

    @Test
    void retrieve_populatesMetadataWithCandidateCounts() {
        var request = RetrievalTestFixtures.retrievalRequest("test", RetrievalRequest.RetrievalScope.BOTH);
        when(vectorRetriever.retrieveCode(any())).thenReturn(RetrievalTestFixtures.rankedDocs(5));
        when(vectorRetriever.retrieveLogs(any())).thenReturn(RetrievalTestFixtures.rankedDocs(3));
        when(bm25Retriever.retrieveCode(any())).thenReturn(RetrievalTestFixtures.rankedDocs(4));
        when(bm25Retriever.retrieveLogs(any())).thenReturn(RetrievalTestFixtures.rankedDocs(2));
        when(graphRetriever.retrieve(any())).thenReturn(RetrievalTestFixtures.rankedDocs(1));
        when(rrfFusion.fuse(anyList())).thenReturn(RetrievalTestFixtures.rankedDocs(10));
        when(reranker.rerank(any(), anyList(), anyInt())).thenReturn(List.of());
        when(meterRegistry.timer(any())).thenReturn(timer);

        var result = service.retrieve(request);

        assertThat(result.metadata().vectorCandidates()).isEqualTo(8);
        assertThat(result.metadata().bm25Candidates()).isEqualTo(6);
        assertThat(result.metadata().graphCandidates()).isEqualTo(1);
    }

    @Test
    void resolveSource_handlesStringAndEnumValues() throws Exception {
        var method = HybridRetrievalService.class.getDeclaredMethod(
            "resolveSource", java.util.Map.class);
        method.setAccessible(true);

        var fromEnum = method.invoke(service,
            java.util.Map.of("original_source", RankedDocument.DocumentSource.BM25_LOG));
        assertThat(fromEnum).isEqualTo(RankedDocument.DocumentSource.BM25_LOG);

        var fromString = method.invoke(service,
            java.util.Map.of("original_source", "GRAPH"));
        assertThat(fromString).isEqualTo(RankedDocument.DocumentSource.GRAPH);
    }
}
