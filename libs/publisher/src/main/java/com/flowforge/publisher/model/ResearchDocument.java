package com.flowforge.publisher.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Root model for the system-flows-research document.
 */
public record ResearchDocument(
    String title,
    Instant generatedAt,
    UUID snapshotId,
    ExecutiveSummary executiveSummary,
    List<FlowSection> flowSections,
    RiskMatrix riskMatrix,
    MigrationRoadmap roadmap,
    List<AppendixItem> appendices,
    DocumentMetadata metadata
) {
}
