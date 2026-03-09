package com.flowforge.publisher.model;

/**
 * Metadata about the generated document (model, tokens, latency).
 */
public record DocumentMetadata(String model, int totalTokensUsed, long totalLatencyMs) {
}
