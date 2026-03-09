package com.flowforge.retrieval.model;

import java.util.Map;

public record RankedDocument(
    String content,
    double score,
    DocumentSource source,
    Map<String, Object> metadata
) {
    public enum DocumentSource { VECTOR_CODE, VECTOR_LOG, BM25_CODE, BM25_LOG, GRAPH }
}
