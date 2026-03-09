package com.flowforge.embedding.service;

import java.util.UUID;

public record LogEmbeddingStats(UUID snapshotId, int templates, int events, int dimensions, String model) {}
