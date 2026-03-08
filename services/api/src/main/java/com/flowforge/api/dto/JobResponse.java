package com.flowforge.api.dto;

import java.util.UUID;

public record JobResponse(UUID jobId, String status, String message) {}
