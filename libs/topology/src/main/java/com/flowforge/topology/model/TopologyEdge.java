package com.flowforge.topology.model;

import java.util.Map;

public record TopologyEdge(
    String sourceId,
    String targetId,
    EdgeType edgeType,
    Map<String, String> metadata
) {
    public enum EdgeType {
        HTTP_CALL,
        KAFKA_PRODUCE,
        KAFKA_CONSUME,
        DATABASE_CONNECT,
        CONFIG_REF,
        INGRESS_ROUTE,
        SERVICE_DEPENDENCY
    }
}
