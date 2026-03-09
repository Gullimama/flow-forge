package com.flowforge.orchestrator.service;

public record TaskStatus(
    String name,
    String phase,
    String startedAt,
    String finishedAt,
    String message
) {}

