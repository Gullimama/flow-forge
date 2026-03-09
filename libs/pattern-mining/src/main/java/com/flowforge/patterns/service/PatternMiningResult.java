package com.flowforge.patterns.service;

/** Result of pattern mining for a snapshot. */
public record PatternMiningResult(
    int traceSequences,
    int temporalSequences,
    int patternsDiscovered,
    long crossServicePatterns,
    long errorPatterns
) {}
