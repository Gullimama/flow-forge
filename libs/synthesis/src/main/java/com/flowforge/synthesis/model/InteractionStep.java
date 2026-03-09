package com.flowforge.synthesis.model;

/**
 * One inter-service interaction in a flow (HTTP, Kafka, gRPC).
 */
public record InteractionStep(
    int order,
    String fromService,
    String toService,
    String protocol,
    String description,
    String dataExchanged
) {
}
