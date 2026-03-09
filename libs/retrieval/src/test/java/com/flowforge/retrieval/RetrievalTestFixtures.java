package com.flowforge.retrieval;

import com.flowforge.retrieval.model.RankedDocument;
import com.flowforge.retrieval.model.RetrievalRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

public final class RetrievalTestFixtures {

    public static final UUID SNAPSHOT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private RetrievalTestFixtures() {}

    public static RetrievalRequest retrievalRequest(String query) {
        return retrievalRequest(query, RetrievalRequest.RetrievalScope.BOTH);
    }

    public static RetrievalRequest retrievalRequest(String query, RetrievalRequest.RetrievalScope scope) {
        return new RetrievalRequest(SNAPSHOT_ID, query, scope, 10, Optional.empty(), Optional.empty());
    }

    public static RankedDocument rankedDoc(String content, double score, RankedDocument.DocumentSource source) {
        return new RankedDocument(content, score, source, java.util.Map.of("content_hash", content));
    }

    public static List<RankedDocument> rankedDocs(int n) {
        return IntStream.range(0, n)
            .mapToObj(i -> rankedDoc("doc-" + i, 1.0 - i * 0.1, RankedDocument.DocumentSource.VECTOR_CODE))
            .toList();
    }
}
