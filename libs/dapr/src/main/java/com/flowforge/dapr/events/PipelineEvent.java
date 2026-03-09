package com.flowforge.dapr.events;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public sealed interface PipelineEvent {

    String type();

    String stage();

    record StageCompletedEvent(
        UUID snapshotId,
        String stage,
        Duration duration,
        Map<String, Object> metadata
    ) implements PipelineEvent {
        @Override
        public String type() {
            return "stage.completed";
        }
    }

    record StageFailedEvent(
        UUID snapshotId,
        String stage,
        String error,
        int attempt
    ) implements PipelineEvent {
        @Override
        public String type() {
            return "stage.failed";
        }
    }

    record SnapshotReadyEvent(
        UUID snapshotId,
        List<String> repoUrls,
        Instant createdAt
    ) implements PipelineEvent {
        @Override
        public String type() {
            return "snapshot.ready";
        }

        @Override
        public String stage() {
            return "orchestrator";
        }
    }
}

