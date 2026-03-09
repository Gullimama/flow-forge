package com.flowforge.retrieval.strategy;

import com.flowforge.common.client.OpenSearchClientWrapper;
import com.flowforge.retrieval.model.RankedDocument;
import com.flowforge.retrieval.model.RetrievalRequest;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BM25Retriever {

    private final OpenSearchClientWrapper openSearch;

    public BM25Retriever(OpenSearchClientWrapper openSearch) {
        this.openSearch = openSearch;
    }

    public List<RankedDocument> retrieveCode(RetrievalRequest request) {
        if (request.scope() == RetrievalRequest.RetrievalScope.LOGS) {
            return List.of();
        }
        try {
            var hits = openSearch.multiMatchSearchWithScores(
                "code-artifacts", request.query(),
                List.of("content", "class_fqn", "method_name", "annotations"),
                request.topK() * 3);
            return hits.stream()
                .map(h -> new RankedDocument(
                    (String) h.getSourceAsMap().getOrDefault("content", ""),
                    h.score(),
                    RankedDocument.DocumentSource.BM25_CODE,
                    new java.util.HashMap<>(h.getSourceAsMap())
                ))
                .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<RankedDocument> retrieveLogs(RetrievalRequest request) {
        if (request.scope() == RetrievalRequest.RetrievalScope.CODE) {
            return List.of();
        }
        try {
            var hits = openSearch.multiMatchSearchWithScores(
                "runtime-events", request.query(),
                List.of("template", "raw_message", "exception_class"),
                request.topK() * 3);
            return hits.stream()
                .map(h -> new RankedDocument(
                    (String) h.getSourceAsMap().getOrDefault("template", ""),
                    h.score(),
                    RankedDocument.DocumentSource.BM25_LOG,
                    new java.util.HashMap<>(h.getSourceAsMap())
                ))
                .toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
