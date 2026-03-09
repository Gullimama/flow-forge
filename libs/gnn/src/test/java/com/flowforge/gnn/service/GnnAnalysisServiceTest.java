package com.flowforge.gnn.service;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.gnn.TestFixtures;
import com.flowforge.gnn.data.GraphData;
import com.flowforge.gnn.data.GraphDataPreparer;
import com.flowforge.gnn.inference.GnnInferenceService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GnnAnalysisServiceTest {

    @Mock GraphDataPreparer dataPreparer;
    @Mock GnnInferenceService inference;
    @Mock MinioStorageClient minio;
    @Mock MeterRegistry meterRegistry;

    @InjectMocks GnnAnalysisService analysisService;

    @BeforeEach
    void setUp() {
        when(meterRegistry.counter(any(String.class))).thenReturn(new SimpleMeterRegistry().counter("test"));
    }

    @Test
    void analyze_orchestratesPreparationAndInference() {
        var snapshotId = UUID.randomUUID();
        var graphData = TestFixtures.sampleGraphData(10, 15);
        when(dataPreparer.prepareGraphData(snapshotId)).thenReturn(graphData);
        when(inference.predictLinks(graphData, 0.7))
            .thenReturn(java.util.List.of(new GnnInferenceService.LinkPrediction(0, 1, 0.85f)));
        when(inference.classifyNodes(graphData))
            .thenReturn(java.util.List.of(new GnnInferenceService.NodeClassification(
                0, GnnInferenceService.InteractionPattern.GATEWAY, 0.9f)));

        var result = analysisService.analyze(snapshotId);

        assertThat(result.predictedLinks()).hasSize(1);
        assertThat(result.nodeClassifications()).hasSize(1);
        assertThat(result.totalNodes()).isEqualTo(10);
        verify(minio).putJson(eq("evidence"),
            eq("gnn-analysis/" + snapshotId + ".json"), eq(result));
    }
}
