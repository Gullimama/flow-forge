package com.flowforge.publisher.model;

import java.util.List;

/**
 * Executive summary section of the research document.
 */
public record ExecutiveSummary(
    String overview,
    int totalFlows,
    int totalServices,
    int criticalRisks,
    int highRisks,
    List<String> topFindings,
    String recommendedApproach
) {
}
