package com.flowforge.flow.builder;

import com.flowforge.flow.FlowBuilderTestFixtures;
import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.graph.query.Neo4jGraphQueryService;
import com.flowforge.retrieval.service.HybridRetrievalService;
import com.flowforge.common.client.MinioStorageClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FlowCandidateBuilderTest {

    @Mock Neo4jGraphQueryService graphQuery;
    @Mock HybridRetrievalService retrieval;
    @Mock MinioStorageClient minio;
    @Mock MeterRegistry meterRegistry;
    @Mock Counter counter;

    @InjectMocks FlowCandidateBuilder builder;

    @BeforeEach
    void stubMetrics() {
        when(meterRegistry.counter(anyString())).thenReturn(counter);
    }

    @Test
    void buildHttpFlows_createsFlowFromCallChain() {
        when(graphQuery.findEntryPointServices()).thenReturn(List.of("api-gateway"));
        when(graphQuery.getCallChainsFrom(eq("api-gateway"), eq(5)))
            .thenReturn(FlowBuilderTestFixtures.callChain("api-gateway", "booking-service", "payment-service"));
        when(graphQuery.findKafkaTopicsWithConnections()).thenReturn(List.of());
        when(retrieval.retrieve(any())).thenReturn(FlowBuilderTestFixtures.emptyRetrievalResult());

        var candidates = builder.buildCandidates(FlowBuilderTestFixtures.SNAPSHOT_ID);

        assertThat(candidates).isNotEmpty();
        var flow = candidates.stream()
            .filter(c -> c.flowType() == FlowCandidate.FlowType.SYNC_REQUEST)
            .findFirst().orElseThrow();
        assertThat(flow.flowType()).isEqualTo(FlowCandidate.FlowType.SYNC_REQUEST);
        assertThat(flow.involvedServices()).containsExactlyInAnyOrder("api-gateway", "booking-service", "payment-service");
        assertThat(flow.steps()).hasSize(3);
    }

    @Test
    void buildKafkaFlows_createsAsyncEventFlow() {
        when(graphQuery.findEntryPointServices()).thenReturn(List.of());
        when(graphQuery.findKafkaTopicsWithConnections()).thenReturn(List.of(
            Map.of("name", "booking-events",
                "producers", List.of("booking-service"),
                "consumers", List.of("notification-service", "analytics-service"))
        ));
        when(retrieval.retrieve(any())).thenReturn(FlowBuilderTestFixtures.emptyRetrievalResult());

        var candidates = builder.buildCandidates(FlowBuilderTestFixtures.SNAPSHOT_ID);

        var kafkaFlows = candidates.stream()
            .filter(c -> c.flowType() == FlowCandidate.FlowType.ASYNC_EVENT)
            .toList();
        assertThat(kafkaFlows).isNotEmpty();
    }

    @Test
    void buildHttpFlows_skipsSingleStepChains() {
        when(graphQuery.findEntryPointServices()).thenReturn(List.of("api-gateway"));
        when(graphQuery.getCallChainsFrom(eq("api-gateway"), eq(5)))
            .thenReturn(FlowBuilderTestFixtures.callChain("api-gateway"));
        when(graphQuery.findKafkaTopicsWithConnections()).thenReturn(List.of());
        when(retrieval.retrieve(any())).thenReturn(FlowBuilderTestFixtures.emptyRetrievalResult());

        var candidates = builder.buildCandidates(FlowBuilderTestFixtures.SNAPSHOT_ID);
        var httpFlows = candidates.stream()
            .filter(c -> c.flowType() == FlowCandidate.FlowType.SYNC_REQUEST)
            .toList();
        assertThat(httpFlows).isEmpty();
    }

    @Test
    void mergeOverlappingFlows_deduplicatesIdenticalServiceChains() {
        when(graphQuery.findEntryPointServices()).thenReturn(List.of("gw"));
        var sameChain = FlowBuilderTestFixtures.callChain("gw", "svc-a", "svc-b");
        when(graphQuery.getCallChainsFrom(eq("gw"), eq(5))).thenReturn(List.of(sameChain.get(0), sameChain.get(0)));
        when(graphQuery.findKafkaTopicsWithConnections()).thenReturn(List.of());
        when(retrieval.retrieve(any())).thenReturn(FlowBuilderTestFixtures.emptyRetrievalResult());

        var candidates = builder.buildCandidates(FlowBuilderTestFixtures.SNAPSHOT_ID);
        long syncFlows = candidates.stream()
            .filter(c -> c.flowType() == FlowCandidate.FlowType.SYNC_REQUEST).count();
        assertThat(syncFlows).isEqualTo(1);
    }

    @Test
    void enrichWithEvidence_usesBatchedQuery_notPerStep() {
        when(graphQuery.findEntryPointServices()).thenReturn(List.of("gw"));
        when(graphQuery.getCallChainsFrom(eq("gw"), eq(5)))
            .thenReturn(FlowBuilderTestFixtures.callChain("gw", "svc-a", "svc-b", "svc-c"));
        when(graphQuery.findKafkaTopicsWithConnections()).thenReturn(List.of());
        when(retrieval.retrieve(any())).thenReturn(FlowBuilderTestFixtures.emptyRetrievalResult());

        builder.buildCandidates(FlowBuilderTestFixtures.SNAPSHOT_ID);

        verify(retrieval, times(1)).retrieve(any());
    }

    @Test
    void buildCandidates_storesEvidenceInMinio() {
        when(graphQuery.findEntryPointServices()).thenReturn(List.of("gw"));
        when(graphQuery.getCallChainsFrom(eq("gw"), eq(5)))
            .thenReturn(FlowBuilderTestFixtures.callChain("gw", "a", "b"));
        when(graphQuery.findKafkaTopicsWithConnections()).thenReturn(List.of());
        when(retrieval.retrieve(any())).thenReturn(FlowBuilderTestFixtures.emptyRetrievalResult());

        builder.buildCandidates(FlowBuilderTestFixtures.SNAPSHOT_ID);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(minio).putJson(eq("evidence"), keyCaptor.capture(), any());
        assertThat(keyCaptor.getValue()).contains("flow-candidates/");
    }
}
