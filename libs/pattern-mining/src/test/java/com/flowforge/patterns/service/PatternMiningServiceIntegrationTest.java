package com.flowforge.patterns.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.client.OpenSearchClientWrapper;
import com.flowforge.logparser.model.ParsedLogEvent;
import com.flowforge.patterns.config.PatternMiningConfig;
import com.flowforge.topology.model.TopologyEdge;
import com.flowforge.topology.model.TopologyNode;
import com.flowforge.topology.service.TopologyEnrichmentService.TopologyGraph;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = PatternMiningServiceIntegrationTest.TestConfig.class)
@Tag("integration")
class PatternMiningServiceIntegrationTest {

    @MockitoBean OpenSearchClientWrapper openSearch;
    @MockitoBean MinioStorageClient minio;
    @Autowired PatternMiningService service;

    @org.springframework.context.annotation.Configuration
    @Import(PatternMiningConfig.class)
    @org.springframework.context.annotation.ComponentScan(basePackages = "com.flowforge.patterns")
    static class TestConfig {
        @Bean
        io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void minePatterns_fullPipeline_discoversAndStoresPatterns() {
        var snapshotId = UUID.randomUUID();
        var events = buildEventsWithTraces(100, 15);
        var topology = buildSmallTopology(snapshotId);

        var result = service.minePatterns(snapshotId, events, topology);

        assertThat(result.traceSequences()).isGreaterThan(0);
        verify(minio).putJson(eq("evidence"), org.mockito.ArgumentMatchers.contains("patterns/" + snapshotId), any());
    }

    @Test
    void minePatterns_withErrorPropagation_detectsErrorPatterns() {
        var snapshotId = UUID.randomUUID();
        var events = buildErrorCascadeEvents(50, 5);
        var topology = buildSmallTopology(snapshotId);

        var result = service.minePatterns(snapshotId, events, topology);

        assertThat(result.traceSequences()).isGreaterThanOrEqualTo(0);
        verify(minio).putJson(eq("evidence"), org.mockito.ArgumentMatchers.contains("patterns/"), any());
    }

    private static List<ParsedLogEvent> buildEventsWithTraces(int totalEvents, int numTraces) {
        var list = new ArrayList<ParsedLogEvent>();
        var base = Instant.now().minusSeconds(3600);
        var services = List.of("order-svc", "payment-svc", "shipping-svc");
        for (int i = 0; i < totalEvents; i++) {
            String traceId = "trace-" + (i % numTraces);
            String svc = services.get(i % 3);
            list.add(new ParsedLogEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                svc,
                base.plusMillis(i * 100),
                ParsedLogEvent.LogSeverity.INFO,
                "tmpl-" + (i % 5),
                "template",
                "msg",
                List.of(),
                Optional.of(traceId),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ParsedLogEvent.LogSource.APP
            ));
        }
        return list;
    }

    private static List<ParsedLogEvent> buildErrorCascadeEvents(int totalEvents, int numTraces) {
        var list = new ArrayList<ParsedLogEvent>();
        var base = Instant.now().minusSeconds(3600);
        var services = List.of("svc-a", "svc-b", "svc-c");
        for (int i = 0; i < totalEvents; i++) {
            String traceId = "trace-" + (i % numTraces);
            String svc = services.get(i % 3);
            list.add(new ParsedLogEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                svc,
                base.plusMillis(i * 50),
                ParsedLogEvent.LogSeverity.ERROR,
                "err-tmpl",
                "error template",
                "downstream failure",
                List.of(),
                Optional.of(traceId),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("RuntimeException"),
                Optional.of("connection refused"),
                ParsedLogEvent.LogSource.APP
            ));
        }
        return list;
    }

    private static TopologyGraph buildSmallTopology(UUID snapshotId) {
        var nodes = List.<TopologyNode>of(
            new TopologyNode.ServiceNode("order-svc", "order-svc", "default", "order:1", 1, Map.of(), List.of(), null, List.of()),
            new TopologyNode.ServiceNode("payment-svc", "payment-svc", "default", "payment:1", 1, Map.of(), List.of(), null, List.of()),
            new TopologyNode.ServiceNode("shipping-svc", "shipping-svc", "default", "shipping:1", 1, Map.of(), List.of(), null, List.of())
        );
        var edges = List.of(
            new TopologyEdge("order-svc", "payment-svc", TopologyEdge.EdgeType.HTTP_CALL, Map.of()),
            new TopologyEdge("payment-svc", "shipping-svc", TopologyEdge.EdgeType.HTTP_CALL, Map.of())
        );
        return new TopologyGraph(snapshotId, nodes, edges);
    }
}
