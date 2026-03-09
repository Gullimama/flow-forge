package com.flowforge.synthesis.model;

/**
 * Key finding with severity for the final narrative.
 */
public record KeyFinding(
    String title,
    String description,
    KeyFinding.FindingSeverity severity,
    String evidence
) {
    public enum FindingSeverity { INFO, WARNING, CRITICAL }
}
