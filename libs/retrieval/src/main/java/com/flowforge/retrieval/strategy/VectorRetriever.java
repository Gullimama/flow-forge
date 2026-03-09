package com.flowforge.retrieval.strategy;

import com.flowforge.retrieval.model.RankedDocument;
import com.flowforge.retrieval.model.RetrievalRequest;
import com.flowforge.vectorstore.service.VectorStoreService;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

@Component
public class VectorRetriever {

    private final VectorStoreService vectorStoreService;

    public VectorRetriever(VectorStoreService vectorStoreService) {
        this.vectorStoreService = vectorStoreService;
    }

    public List<RankedDocument> retrieveCode(RetrievalRequest request) {
        if (request.scope() == RetrievalRequest.RetrievalScope.LOGS) {
            return List.of();
        }
        var docs = vectorStoreService.searchCode(
            request.query(), request.topK() * 3, request.snapshotId().toString());
        return docs.stream()
            .map(d -> toRankedDocument(d, RankedDocument.DocumentSource.VECTOR_CODE))
            .toList();
    }

    public List<RankedDocument> retrieveLogs(RetrievalRequest request) {
        if (request.scope() == RetrievalRequest.RetrievalScope.CODE) {
            return List.of();
        }
        String queryText = "query: " + request.query();
        var docs = vectorStoreService.searchLogs(
            queryText, request.topK() * 3,
            request.snapshotId().toString(), request.serviceName());
        return docs.stream()
            .map(d -> toRankedDocument(d, RankedDocument.DocumentSource.VECTOR_LOG))
            .toList();
    }

    private static RankedDocument toRankedDocument(Document d, RankedDocument.DocumentSource source) {
        double score = 0.0;
        var meta = d.getMetadata();
        if (meta != null) {
            Object dist = meta.get("distance");
            if (dist instanceof Number n) score = n.doubleValue();
            else if (meta.get("score") instanceof Number n) score = n.doubleValue();
        }
        return new RankedDocument(
            d.getText(),
            score,
            source,
            meta != null ? new java.util.HashMap<>(meta) : new java.util.HashMap<>()
        );
    }
}
