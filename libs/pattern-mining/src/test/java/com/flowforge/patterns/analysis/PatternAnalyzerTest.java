package com.flowforge.patterns.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.patterns.extract.CallSequenceExtractor.SequenceItem;
import com.flowforge.patterns.mining.SequencePatternMiner;
import com.flowforge.patterns.mining.SequencePatternMiner.DiscoveredPattern;
import com.flowforge.patterns.mining.SequencePatternMiner.PatternType;
import com.flowforge.topology.service.TopologyEnrichmentService.TopologyGraph;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatternAnalyzerTest {

    private final PatternAnalyzer analyzer = new PatternAnalyzer();

    private static TopologyGraph emptyTopology() {
        return new TopologyGraph(UUID.randomUUID(), List.of(), List.of());
    }

    private static SequenceItem seqItem(String service, String eventType, String detail) {
        return new SequenceItem(service, eventType, detail);
    }

    private static DiscoveredPattern discoveredPattern(List<SequenceItem> items) {
        return new DiscoveredPattern(
            UUID.randomUUID(),
            items,
            items.size(),
            0.5,
            SequencePatternMiner.classifyPatternType(items)
        );
    }

    @Test
    void analyzePatterns_errorPropagation_identifiedCorrectly() {
        var pattern = discoveredPattern(List.of(
            seqItem("svc-a", "LOG_ERROR", "timeout"),
            seqItem("svc-b", "LOG_ERROR", "connection refused"),
            seqItem("svc-c", "LOG_ERROR", "downstream failure")
        ));

        var result = analyzer.analyzePatterns(List.of(pattern), emptyTopology());

        assertThat(result.getFirst().hasErrors()).isTrue();
        assertThat(result.getFirst().crossesServiceBoundary()).isTrue();
        assertThat(pattern.patternType()).isEqualTo(PatternType.ERROR_PROPAGATION);
    }

    @Test
    void analyzePatterns_kafkaFanout_flaggedAsKafkaInvolved() {
        var pattern = discoveredPattern(List.of(
            seqItem("svc-a", "KAFKA_PRODUCE", "orders-topic"),
            seqItem("svc-b", "KAFKA_CONSUME", "orders-topic"),
            seqItem("svc-c", "KAFKA_CONSUME", "orders-topic")
        ));

        var result = analyzer.analyzePatterns(List.of(pattern), emptyTopology());

        assertThat(result.getFirst().involvesKafka()).isTrue();
    }

    @Test
    void analyzePatterns_singleServicePattern_notCrossService() {
        var pattern = discoveredPattern(List.of(
            seqItem("svc-a", "ENDPOINT_CALL", "/step1"),
            seqItem("svc-a", "ENDPOINT_CALL", "/step2")
        ));

        var result = analyzer.analyzePatterns(List.of(pattern), emptyTopology());

        assertThat(result.getFirst().crossesServiceBoundary()).isFalse();
    }

    @Test
    void generatePatternDescription_producesReadableArrowFormat() {
        var pattern = discoveredPattern(List.of(
            seqItem("order-svc", "ENDPOINT_CALL", "/orders"),
            seqItem("payment-svc", "ENDPOINT_CALL", "/pay")
        ));

        var result = analyzer.analyzePatterns(List.of(pattern), emptyTopology());

        assertThat(result.getFirst().description())
            .contains("order-svc::ENDPOINT_CALL(/orders)")
            .contains("→")
            .contains("payment-svc::ENDPOINT_CALL(/pay)");
    }

    @Test
    void analyzePatterns_retryPattern_classifiedCorrectly() {
        var pattern = discoveredPattern(List.of(
            seqItem("svc-a", "ENDPOINT_CALL", "/pay"),
            seqItem("svc-a", "ENDPOINT_CALL", "/pay"),
            seqItem("svc-a", "ENDPOINT_CALL", "/pay")
        ));

        assertThat(pattern.patternType()).isEqualTo(PatternType.RETRY_PATTERN);
    }
}
