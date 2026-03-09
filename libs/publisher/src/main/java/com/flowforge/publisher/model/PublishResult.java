package com.flowforge.publisher.model;

/**
 * Result of publishing the research document.
 */
public record PublishResult(String outputKey, int markdownLength, int flowCount, int riskCount) {
}
