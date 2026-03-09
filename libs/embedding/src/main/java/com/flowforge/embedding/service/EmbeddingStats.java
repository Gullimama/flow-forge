package com.flowforge.embedding.service;

import java.util.UUID;

public record EmbeddingStats(UUID snapshotId, int documentCount, int dimensions, String model) {}
