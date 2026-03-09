package com.flowforge.reranker.resilient;

import com.flowforge.reranker.client.CrossEncoderReranker;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

@Component
public class ResilientReranker {

    private static final Logger log = LoggerFactory.getLogger(ResilientReranker.class);

    private final CrossEncoderReranker reranker;

    public ResilientReranker(CrossEncoderReranker reranker) {
        this.reranker = reranker;
    }

    @CircuitBreaker(name = "reranker", fallbackMethod = "fallbackRerank")
    @Retry(name = "reranker")
    public List<Document> rerank(String query, List<Document> documents, int topK) {
        return reranker.rerankDocuments(query, documents, topK);
    }

    /**
     * Fallback: return documents sorted by their original similarity score
     * when the reranker is unavailable.
     */
    @SuppressWarnings("unused")
    private List<Document> fallbackRerank(String query, List<Document> documents, int topK, Throwable t) {
        log.warn("Reranker unavailable, falling back to bi-encoder scores: {}", t.getMessage());
        return documents.stream()
            .sorted(Comparator.comparingDouble(
                (Document d) -> ((Number) d.getMetadata().getOrDefault("score", 0.0)).doubleValue()).reversed())
            .limit(topK)
            .toList();
    }
}
