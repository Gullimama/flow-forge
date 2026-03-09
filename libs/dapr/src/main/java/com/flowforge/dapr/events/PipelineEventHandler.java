package com.flowforge.dapr.events;

public interface PipelineEventHandler {

    default void onStageCompleted(PipelineEvent.StageCompletedEvent event) {}

    default void onStageFailed(PipelineEvent.StageFailedEvent event) {}

    default void onSnapshotReady(PipelineEvent.SnapshotReadyEvent event) {}
}

