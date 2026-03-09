package com.flowforge.reranker.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.ai.document.Document;

@Component
public class CrossEncoderReranker {

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;

    public CrossEncoderReranker(
            @org.springframework.beans.factory.annotation.Qualifier("rerankerRestClient") RestClient restClient,
            MeterRegistry meterRegistry) {
        this.restClient = restClient;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Reranker request model matching TEI's /rerank endpoint.
     */
    public record RerankRequest(
        String query,
        List<String> texts,
        @JsonProperty("return_text") boolean returnText
    ) {}

    /**
     * TEI returns a flat array of {index, score, text} objects.
     */
    public record RerankResult(int index, double score, String text) {}

    /**
     * Rerank a list of texts against a query.
     * Returns results sorted by relevance score (descending).
     */
    public List<RerankResult> rerank(String query, List<String> texts, int topK) {
        if (texts.isEmpty()) return List.of();

        var request = new RerankRequest(query, texts, true);

        Timer.Sample sample = Timer.start(meterRegistry);
        List<RerankResult> results;
        try {
            results = restClient.post()
                .uri("/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<List<RerankResult>>() {});
        } finally {
            sample.stop(meterRegistry.timer("flowforge.reranker.latency"));
        }

        if (results == null || results.isEmpty()) return List.of();

        return results.stream()
            .sorted(Comparator.comparingDouble(RerankResult::score).reversed())
            .limit(topK)
            .toList();
    }

    /**
     * Rerank Spring AI Documents, preserving metadata and adding reranker_score.
     */
    public List<Document> rerankDocuments(String query, List<Document> documents, int topK) {
        if (documents.isEmpty()) return List.of();

        var texts = documents.stream()
            .map(Document::getText)
            .toList();

        var ranked = rerank(query, texts, topK);

        return ranked.stream()
            .map(r -> {
                var doc = documents.get(r.index());
                var meta = new HashMap<>(doc.getMetadata());
                meta.put("reranker_score", r.score());
                return new Document(doc.getText(), meta);
            })
            .toList();
    }
}
