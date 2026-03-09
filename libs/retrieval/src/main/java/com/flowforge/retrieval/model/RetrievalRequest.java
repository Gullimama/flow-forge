package com.flowforge.retrieval.model;

import java.util.Optional;
import java.util.UUID;

public record RetrievalRequest(
    UUID snapshotId,
    String query,
    RetrievalScope scope,
    int topK,
    Optional<String> serviceName,
    Optional<Integer> graphHops
) {
    public enum RetrievalScope { CODE, LOGS, BOTH }
}
