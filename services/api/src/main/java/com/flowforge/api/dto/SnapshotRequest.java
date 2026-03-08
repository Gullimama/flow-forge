package com.flowforge.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SnapshotRequest(
    @NotBlank String repoUrl,
    String githubToken
) {}
