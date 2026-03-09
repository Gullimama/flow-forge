package com.flowforge.anomaly.training;

import com.flowforge.anomaly.feature.LogFeatureEngineer.LogFeatureVector;
import com.flowforge.anomaly.model.AnomalyDetectorModel;
import com.flowforge.mlflow.service.ExperimentTracker;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TrackedAnomalyTrainer {

    private static final Logger log = LoggerFactory.getLogger(TrackedAnomalyTrainer.class);

    private final AnomalyDetectorModel baseModel;
    private final ExperimentTracker tracker;

    public TrackedAnomalyTrainer(AnomalyDetectorModel baseModel, ExperimentTracker tracker) {
        this.baseModel = baseModel;
        this.tracker = tracker;
    }

    /**
     * Train an anomaly detector with MLflow tracking for a given snapshot.
     */
    public AnomalyDetectorModel trainTracked(String snapshotId, List<LogFeatureVector> features) {
        var params = Map.of(
            "snapshot_id", snapshotId,
            "model_type", "isolation_forest",
            "num_trees", String.valueOf(baseModel.getNumTrees()),
            "subsample_size", String.valueOf(baseModel.getSubsampleSize()),
            "feature_count", String.valueOf(features.size()),
            "feature_dim", "8"
        );

        return tracker.trackRun("anomaly-" + snapshotId, params, ctx -> {
            var model = new AnomalyDetectorModel(
                baseModel.getNumTrees(), baseModel.getSubsampleSize());
            model.train(features);

            ctx.logMetric("training_samples", features.size());
            try {
                var modelBytes = model.serializeModel();
                ctx.logModel("models/anomaly-model.smile", modelBytes);
            } catch (IOException e) {
                log.warn("Failed to serialize anomaly model for snapshot {}", snapshotId, e);
            }

            return model;
        });
    }
}

