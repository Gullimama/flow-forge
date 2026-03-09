package com.flowforge.dapr.api;

import com.flowforge.dapr.events.CloudEvent;
import com.flowforge.dapr.events.DaprSubscription;
import com.flowforge.dapr.events.PipelineEvent;
import com.flowforge.dapr.events.PipelineEventHandler;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DaprEventSubscriber {

    private static final Logger log = LoggerFactory.getLogger(DaprEventSubscriber.class);

    private final List<PipelineEventHandler> handlers;

    public DaprEventSubscriber(List<PipelineEventHandler> handlers) {
        this.handlers = handlers;
    }

    @GetMapping("/dapr/subscribe")
    public List<DaprSubscription> subscribe() {
        return List.of(
            new DaprSubscription("flowforge-pubsub", "pipeline.stage.completed", "/api/events/stage-completed"),
            new DaprSubscription("flowforge-pubsub", "pipeline.stage.failed", "/api/events/stage-failed"),
            new DaprSubscription("flowforge-pubsub", "pipeline.snapshot.ready", "/api/events/snapshot-ready")
        );
    }

    @PostMapping("/api/events/stage-completed")
    public ResponseEntity<Void> onStageCompleted(@RequestBody CloudEvent<PipelineEvent.StageCompletedEvent> event) {
        log.info("Stage completed: {} for snapshot {}", event.data().stage(), event.data().snapshotId());
        handlers.forEach(h -> h.onStageCompleted(event.data()));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/events/stage-failed")
    public ResponseEntity<Void> onStageFailed(@RequestBody CloudEvent<PipelineEvent.StageFailedEvent> event) {
        log.warn("Stage failed: {} - {}", event.data().stage(), event.data().error());
        handlers.forEach(h -> h.onStageFailed(event.data()));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/events/snapshot-ready")
    public ResponseEntity<Void> onSnapshotReady(@RequestBody CloudEvent<PipelineEvent.SnapshotReadyEvent> event) {
        log.info("Snapshot ready: {}", event.data().snapshotId());
        handlers.forEach(h -> h.onSnapshotReady(event.data()));
        return ResponseEntity.ok().build();
    }
}

