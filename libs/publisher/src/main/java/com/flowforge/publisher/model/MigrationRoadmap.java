package com.flowforge.publisher.model;

import java.util.List;

/**
 * Migration roadmap section.
 */
public record MigrationRoadmap(
    List<RoadmapPhase> phases,
    String totalEstimatedDuration,
    String recommendedTeamSize
) {
}
