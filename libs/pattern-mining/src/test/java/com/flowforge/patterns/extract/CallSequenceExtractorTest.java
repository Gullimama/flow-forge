package com.flowforge.patterns.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.logparser.model.ParsedLogEvent;
import com.flowforge.topology.service.TopologyEnrichmentService.TopologyGraph;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CallSequenceExtractorTest {

    private final CallSequenceExtractor extractor = new CallSequenceExtractor();

    private static TopologyGraph emptyTopology() {
        return new TopologyGraph(UUID.randomUUID(), List.of(), List.of());
    }

    private static ParsedLogEvent logEvent(String service, String traceId, Instant ts) {
        return new ParsedLogEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            service,
            ts,
            ParsedLogEvent.LogSeverity.INFO,
            "tmpl",
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
        );
    }

    private static ParsedLogEvent logEventNoTrace(String service, Instant ts) {
        return new ParsedLogEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            service,
            ts,
            ParsedLogEvent.LogSeverity.INFO,
            "tmpl",
            "template",
            "msg",
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            ParsedLogEvent.LogSource.APP
        );
    }

    @Test
    void extractFromTraces_groupsByTraceId() {
        var now = Instant.now();
        var events = List.of(
            logEvent("order-svc", "trace-1", now),
            logEvent("payment-svc", "trace-1", now.plusMillis(50)),
            logEvent("order-svc", "trace-2", now),
            logEvent("shipping-svc", "trace-2", now.plusMillis(100)),
            logEvent("shipping-svc", "trace-2", now.plusMillis(200))
        );
        var topology = emptyTopology();

        var sequences = extractor.extractFromTraces(events, topology);

        assertThat(sequences).hasSize(2);
        assertThat(sequences.get(0)).hasSize(2);
        assertThat(sequences.get(1)).hasSize(3);
    }

    @Test
    void extractFromTraces_filtersOutEventsWithoutTraceId() {
        var now = Instant.now();
        var events = List.of(
            logEvent("svc-a", "trace-1", now),
            logEvent("svc-b", "trace-1", now.plusMillis(10)),
            logEventNoTrace("svc-c", now)
        );

        var sequences = extractor.extractFromTraces(events, emptyTopology());

        assertThat(sequences).hasSize(1);
        assertThat(sequences.getFirst()).hasSize(2);
    }

    @Test
    void extractFromTraces_singleEventTrace_excluded() {
        var events = List.of(
            logEvent("svc-a", "trace-lonely", Instant.now())
        );

        var sequences = extractor.extractFromTraces(events, emptyTopology());

        assertThat(sequences).isEmpty();
    }

    @Test
    void extractFromTraces_sortsEventsByTimestamp() {
        var now = Instant.now();
        var events = List.of(
            logEvent("svc-b", "trace-1", now.plusMillis(100)),
            logEvent("svc-a", "trace-1", now)
        );

        var sequences = extractor.extractFromTraces(events, emptyTopology());

        assertThat(sequences.getFirst().get(0).serviceName()).isEqualTo("svc-a");
        assertThat(sequences.getFirst().get(1).serviceName()).isEqualTo("svc-b");
    }

    @Test
    void extractFromTemporalWindows_splitsIntoWindows() {
        var now = Instant.now();
        var events = IntStream.range(0, 10)
            .mapToObj(i -> logEventNoTrace("svc-" + (i % 3), now.plusSeconds(i * 5)))
            .toList();

        var sequences = extractor.extractFromTemporalWindows(events, java.time.Duration.ofSeconds(30));

        assertThat(sequences).isNotEmpty();
        sequences.forEach(seq -> assertThat(seq.size()).isGreaterThanOrEqualTo(2));
    }

    @Test
    void extractFromTemporalWindows_lessThanTwoEvents_returnsEmpty() {
        var events = List.of(logEventNoTrace("svc-a", Instant.now()));
        var sequences = extractor.extractFromTemporalWindows(events, java.time.Duration.ofSeconds(30));
        assertThat(sequences).isEmpty();
    }

    @Test
    void sequenceItemEncode_threadSafe_noDuplicateIds() throws Exception {
        var itemMap = new ConcurrentHashMap<CallSequenceExtractor.SequenceItem, Integer>();
        var counter = new AtomicInteger(0);

        var items = List.of(
            new CallSequenceExtractor.SequenceItem("svc-a", "ENDPOINT_CALL", "/orders"),
            new CallSequenceExtractor.SequenceItem("svc-b", "KAFKA_PRODUCE", "topic-1"),
            new CallSequenceExtractor.SequenceItem("svc-a", "ENDPOINT_CALL", "/orders")
        );

        ExecutorService executor = Executors.newFixedThreadPool(4);
        var futures = items.stream()
            .map(item -> executor.submit(() -> item.encode(itemMap, counter)))
            .toList();

        var ids = futures.stream().map(f -> {
            try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList();

        assertThat(ids.get(0)).isEqualTo(ids.get(2));
        assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
        assertThat(itemMap).hasSize(2);
        executor.shutdown();
    }
}
