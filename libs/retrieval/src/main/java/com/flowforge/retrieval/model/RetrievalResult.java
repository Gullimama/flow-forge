package com.flowforge.retrieval.model;

import java.util.List;

public record RetrievalResult(
    String query,
    List<RankedDocument> documents,
    RetrievalMetadata metadata
) {}
