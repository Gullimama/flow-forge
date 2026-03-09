package com.flowforge.orchestrator.service;

public record WorkflowStatus(
    String name,
    String phase,
    String startedAt
) {}

