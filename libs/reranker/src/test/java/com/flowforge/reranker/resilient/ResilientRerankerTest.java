package com.flowforge.reranker.resilient;

import com.flowforge.reranker.client.CrossEncoderReranker;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResilientRerankerTest {

    @Mock
    CrossEncoderReranker crossEncoderReranker;
    @InjectMocks
    ResilientReranker resilientReranker;

    @Test
    void fallback_returnsBiEncoderOrder() throws Exception {
        var docs = List.of(
            new Document("high similarity", java.util.Map.of("score", 0.95)),
            new Document("low similarity", java.util.Map.of("score", 0.3)),
            new Document("mid similarity", java.util.Map.of("score", 0.7))
        );

        var method = ResilientReranker.class.getDeclaredMethod(
            "fallbackRerank", String.class, List.class, int.class, Throwable.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        var result = (List<Document>) method.invoke(resilientReranker,
            "query", docs, 2, new RuntimeException("TEI down"));

        assertThat(result).hasSize(2);
        assertThat(((Number) result.get(0).getMetadata().get("score")).doubleValue()).isEqualTo(0.95);
    }

    @Test
    void rerank_delegatesToCrossEncoder_whenHealthy() {
        var expected = List.of(new Document("reranked", java.util.Map.of()));
        when(crossEncoderReranker.rerankDocuments("q", List.of(), 5)).thenReturn(expected);

        var result = resilientReranker.rerank("q", List.of(), 5);
        assertThat(result).isEqualTo(expected);
    }
}
