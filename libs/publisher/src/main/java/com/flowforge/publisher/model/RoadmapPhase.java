package com.flowforge.publisher.model;

import java.util.List;

/**
 * One phase in the migration roadmap.
 */
public record RoadmapPhase(
    int order,
    String name,
    String duration,
    List<String> flows,
    List<String> deliverables
) {
}
