package com.flowforge.eval.retrieval;

import java.util.Set;

public record RetrievalTestCase(
    String queryId,
    String query,
    Set<String> relevantIds,
    int topK
) {}

