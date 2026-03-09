package com.flowforge.gnn.inference;

import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import com.flowforge.gnn.data.GraphData;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * GNN inference via DJL ONNX Runtime: link prediction and node classification.
 */
@Service
@ConditionalOnBean(name = "gnnLinkPredictionCriteria")
public class GnnInferenceService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GnnInferenceService.class);

    private final ZooModel<NDList, NDList> linkPredModel;
    private final ZooModel<NDList, NDList> nodeClassModel;
    private final MeterRegistry meterRegistry;

    public GnnInferenceService(
            Criteria<NDList, NDList> gnnLinkPredictionCriteria,
            Criteria<NDList, NDList> gnnNodeClassificationCriteria,
            MeterRegistry meterRegistry) throws Exception {
        this.linkPredModel = gnnLinkPredictionCriteria.loadModel();
        this.nodeClassModel = gnnNodeClassificationCriteria.loadModel();
        this.meterRegistry = meterRegistry;
    }

    /**
     * Constructor for tests that inject mock ZooModels.
     */
    public GnnInferenceService(
            ZooModel<NDList, NDList> linkPredModel,
            ZooModel<NDList, NDList> nodeClassModel,
            MeterRegistry meterRegistry) {
        this.linkPredModel = linkPredModel;
        this.nodeClassModel = nodeClassModel;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Predict missing links (edges) in the service graph.
     * Returns pairs of node indices with probability scores.
     */
    public List<LinkPrediction> predictLinks(GraphData graphData, double threshold) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            try (var predictor = linkPredModel.newPredictor();
                 var manager = NDManager.newBaseManager()) {

                var nodeFeatures = manager.create(graphData.nodeFeatures());
                var edgeIndex = manager.create(graphData.edgeIndex());

                var input = new NDList(nodeFeatures, edgeIndex);
                var output = predictor.predict(input);

                var scores = output.get(0).toFloatArray();
                int numNodes = graphData.numNodes();

                var predictions = new ArrayList<LinkPrediction>();
                for (int i = 0; i < numNodes; i++) {
                    for (int j = i + 1; j < numNodes; j++) {
                        int idx = i * numNodes + j;
                        if (idx < scores.length && scores[idx] > threshold) {
                            predictions.add(new LinkPrediction(i, j, scores[idx]));
                        }
                    }
                }
                return predictions;
            }
        } catch (Exception e) {
            log.error("GNN link prediction failed", e);
            return List.of();
        } finally {
            sample.stop(meterRegistry.timer("flowforge.gnn.link_prediction.latency"));
        }
    }

    /**
     * Classify nodes into interaction pattern categories.
     */
    public List<NodeClassification> classifyNodes(GraphData graphData) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            try (var predictor = nodeClassModel.newPredictor();
                 var manager = NDManager.newBaseManager()) {

                var nodeFeatures = manager.create(graphData.nodeFeatures());
                var edgeIndex = manager.create(graphData.edgeIndex());

                var input = new NDList(nodeFeatures, edgeIndex);
                var output = predictor.predict(input);

                var probabilities = output.get(0);
                var classifications = new ArrayList<NodeClassification>();
                int numNodes = graphData.numNodes();
                var allProbs = probabilities.toFloatArray();
                var shape = probabilities.getShape();
                long numClasses = shape.dimension() >= 2 ? shape.get(1) : (allProbs.length / Math.max(1, numNodes));

                for (int i = 0; i < numNodes; i++) {
                    int nc = (int) numClasses;
                    var nodeProbs = new float[nc];
                    if (i * nc + nc <= allProbs.length) {
                        System.arraycopy(allProbs, i * nc, nodeProbs, 0, nc);
                    }
                    int bestClass = argmax(nodeProbs);
                    classifications.add(new NodeClassification(
                        i,
                        InteractionPattern.values()[Math.min(bestClass, InteractionPattern.values().length - 1)],
                        nodeProbs[bestClass]
                    ));
                }
                return classifications;
            }
        } catch (Exception e) {
            log.error("GNN node classification failed", e);
            return List.of();
        } finally {
            sample.stop(meterRegistry.timer("flowforge.gnn.node_classification.latency"));
        }
    }

    private static int argmax(float[] arr) {
        int best = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > arr[best]) {
                best = i;
            }
        }
        return best;
    }

    @Override
    public void close() {
        if (linkPredModel != null) {
            linkPredModel.close();
        }
        if (nodeClassModel != null) {
            nodeClassModel.close();
        }
    }

    public record LinkPrediction(int sourceNodeIdx, int targetNodeIdx, float probability) {}
    public record NodeClassification(int nodeIdx, InteractionPattern pattern, float confidence) {}

    public enum InteractionPattern {
        GATEWAY, ORCHESTRATOR, WORKER, DATA_STORE_CONNECTOR,
        EVENT_PRODUCER, EVENT_CONSUMER, MIDDLEWARE
    }
}
