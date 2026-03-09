package com.flowforge.gnn.training;

import com.flowforge.gnn.data.GraphData;
import com.flowforge.gnn.inference.GnnInferenceService;
import com.flowforge.mlflow.service.ExperimentTracker;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TrackedGnnTrainer {

    private final GnnInferenceService inference;
    private final ExperimentTracker tracker;

    public TrackedGnnTrainer(GnnInferenceService inference, ExperimentTracker tracker) {
        this.inference = inference;
        this.tracker = tracker;
    }

    /**
     * Run GNN inference with MLflow tracking for metrics.
     */
    public void runTracked(GraphData graphData, double linkThreshold) {
        var params = Map.of(
            "num_nodes", String.valueOf(graphData.numNodes()),
            "num_edges", String.valueOf(graphData.numEdges()),
            "link_threshold", String.valueOf(linkThreshold)
        );

        tracker.trackRun("gnn-training", params, ctx -> {
            var linkResult = inference.predictLinks(graphData, linkThreshold);
            ctx.logMetric("predicted_links", linkResult.size());

            var nodeResult = inference.classifyNodes(graphData);
            ctx.logMetric("classified_nodes", nodeResult.size());

            return null;
        });
    }
}

