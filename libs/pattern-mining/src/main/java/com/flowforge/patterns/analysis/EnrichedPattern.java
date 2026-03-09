package com.flowforge.patterns.analysis;

import com.flowforge.patterns.mining.SequencePatternMiner.DiscoveredPattern;
import java.util.List;

/** Pattern enriched with domain analysis for synthesis and evidence. */
public record EnrichedPattern(
    DiscoveredPattern pattern,
    List<String> involvedServices,
    boolean crossesServiceBoundary,
    boolean hasErrors,
    boolean involvesKafka,
    String description
) {}
