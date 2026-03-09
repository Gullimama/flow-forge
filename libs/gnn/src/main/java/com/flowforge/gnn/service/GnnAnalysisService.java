package com.flowforge.gnn.service;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.gnn.data.GraphData;
import com.flowforge.gnn.data.GraphDataPreparer;
import com.flowforge.gnn.inference.GnnInferenceService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * Runs full GNN analysis on a snapshot's knowledge graph and stores results in MinIO.
 */
@Service
@ConditionalOnBean(GnnInferenceService.class)
public class GnnAnalysisService {

    private static final double LINK_PREDICTION_THRESHOLD = 0.7;

    private final GraphDataPreparer dataPreparer;
    private final GnnInferenceService inference;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    public GnnAnalysisService(
            GraphDataPreparer dataPreparer,
            GnnInferenceService inference,
            MinioStorageClient minio,
            MeterRegistry meterRegistry) {
        this.dataPreparer = dataPreparer;
        this.inference = inference;
        this.minio = minio;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Run full GNN analysis on a snapshot's knowledge graph.
     */
    public GnnAnalysisResult analyze(UUID snapshotId) {
        var graphData = dataPreparer.prepareGraphData(snapshotId);

        var predictedLinks = inference.predictLinks(graphData, LINK_PREDICTION_THRESHOLD);
        var nodeClasses = inference.classifyNodes(graphData);

        var result = new GnnAnalysisResult(
            snapshotId,
            predictedLinks,
            nodeClasses,
            graphData.numNodes(),
            graphData.numEdges()
        );

        minio.putJson("evidence", "gnn-analysis/" + snapshotId + ".json", result);
        meterRegistry.counter("flowforge.gnn.links.predicted").increment(predictedLinks.size());

        return result;
    }

    public record GnnAnalysisResult(
        UUID snapshotId,
        java.util.List<GnnInferenceService.LinkPrediction> predictedLinks,
        java.util.List<GnnInferenceService.NodeClassification> nodeClassifications,
        int totalNodes,
        int totalEdges
    ) {}
}
