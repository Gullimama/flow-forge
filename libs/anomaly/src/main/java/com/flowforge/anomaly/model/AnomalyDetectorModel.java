package com.flowforge.anomaly.model;

import com.flowforge.anomaly.feature.LogFeatureEngineer.LogFeatureVector;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import smile.anomaly.IsolationForest;

public class AnomalyDetectorModel {

    private final int numTrees;
    private final int subsampleSize;

    private IsolationForest trainedModel;

    public AnomalyDetectorModel(int numTrees, int subsampleSize) {
        this.numTrees = numTrees;
        this.subsampleSize = subsampleSize;
    }

    public int getNumTrees() {
        return numTrees;
    }

    public int getSubsampleSize() {
        return subsampleSize;
    }

    /**
     * Train an Isolation Forest on historical feature vectors.
     */
    public void train(List<LogFeatureVector> features) {
        double[][] matrix = buildFeatureMatrix(features);
        this.trainedModel = IsolationForest.fit(matrix);
    }

    /**
     * Score a feature vector: higher score → more anomalous.
     */
    public double score(LogFeatureVector vector) {
        if (trainedModel == null) {
            throw new IllegalStateException("Model not trained");
        }
        return trainedModel.score(vectorToArray(vector));
    }

    /**
     * Classify as anomalous if score exceeds threshold.
     */
    public boolean isAnomalous(LogFeatureVector vector, double threshold) {
        return score(vector) >= threshold;
    }

    private double[][] buildFeatureMatrix(List<LogFeatureVector> features) {
        return features.stream()
            .map(this::vectorToArray)
            .toArray(double[][]::new);
    }

    private double[] vectorToArray(LogFeatureVector v) {
        return new double[] {
            v.errorRate(), v.uniqueTemplateRatio(), v.eventRate(),
            v.p99Latency(), v.errorBurstScore(), v.newTemplateRate(),
            v.traceSpanRatio(), v.exceptionRate()
        };
    }

    /** Serialize model to bytes (for MLflow/MinIO storage). */
    public byte[] serializeModel() throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var oos = new ObjectOutputStream(baos)) {
            oos.writeObject(trainedModel);
        }
        return baos.toByteArray();
    }

    /** Load model from bytes. */
    public void loadModel(byte[] modelBytes) throws IOException, ClassNotFoundException {
        var bais = new ByteArrayInputStream(modelBytes);
        try (var ois = new ObjectInputStream(bais)) {
            this.trainedModel = (IsolationForest) ois.readObject();
        }
    }
}
