package com.flowforge.retrieval.model;

public record RetrievalMetadata(
    int vectorCandidates,
    int bm25Candidates,
    int graphCandidates,
    int afterFusion,
    int afterReranking,
    long latencyMs
) {}
