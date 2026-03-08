package com.flowforge.anomaly.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.flowforge.logparser.model.ParsedLogEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LogFeatureEngineerTest {

    private final LogFeatureEngineer engineer = new LogFeatureEngineer();

    @Test
    void extractFeatures_emptyEventList_returnsEmptyList() {
        var result = engineer.extractFeatures("order-service", List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void extractFeatures_thirtyMinutesOfLogs_producesSixWindows() {
        var events = generateEventsOverMinutes("order-service", 30, 10);
        var result = engineer.extractFeatures("order-service", events);
        assertThat(result).hasSize(6);
    }

    @Test
    void computeFeatures_errorRate_calculatedCorrectly() {
        var events = List.of(
            logEvent("svc", ParsedLogEvent.LogSeverity.INFO, "tmpl-1"),
            logEvent("svc", ParsedLogEvent.LogSeverity.ERROR, "tmpl-2"),
            logEvent("svc", ParsedLogEvent.LogSeverity.ERROR, "tmpl-3"),
            logEvent("svc", ParsedLogEvent.LogSeverity.FATAL, "tmpl-4")
        );
        var result = engineer.extractFeatures("svc", events);
        assertThat(result.get(0).errorRate()).isCloseTo(0.75, within(0.001));
    }

    @Test
    void computeFeatures_uniqueTemplateRatio_handlesAllSameTemplate() {
        var events = IntStream.range(0, 20)
            .mapToObj(i -> logEvent("svc", ParsedLogEvent.LogSeverity.INFO, "same-template"))
            .toList();
        var result = engineer.extractFeatures("svc", events);
        assertThat(result.get(0).uniqueTemplateRatio()).isCloseTo(0.05, within(0.01));
    }

    @Test
    void computeFeatures_traceSpanRatio_countsEventsWithTraceId() {
        var events = List.of(
            logEventWithTrace("svc", "trace-1"),
            logEventWithTrace("svc", "trace-2"),
            logEventNoTrace("svc"),
            logEventNoTrace("svc")
        );
        var result = engineer.extractFeatures("svc", events);
        assertThat(result.get(0).traceSpanRatio()).isCloseTo(0.5, within(0.001));
    }

    @Test
    void computeFeatures_exceptionRate_countsEventsWithExceptionClass() {
        var events = List.of(
            logEventWithException("svc", "NullPointerException"),
            logEventNoException("svc"),
            logEventNoException("svc")
        );
        var result = engineer.extractFeatures("svc", events);
        assertThat(result.get(0).exceptionRate()).isCloseTo(0.333, within(0.01));
    }

    @Test
    void computeFeatures_eventsUnsorted_areSortedByTimestamp() {
        var now = Instant.now();
        var events = List.of(
            logEventAt("svc", now.plusSeconds(120)),
            logEventAt("svc", now),
            logEventAt("svc", now.plusSeconds(60))
        );
        var result = engineer.extractFeatures("svc", events);
        assertThat(result.get(0).windowStart()).isBeforeOrEqualTo(result.get(0).windowEnd());
    }

    @Test
    void extractFeatures_featureVectorHasEightDimensions() {
        var events = generateEventsOverMinutes("svc", 5, 20);
        var vector = engineer.extractFeatures("svc", events).get(0);
        double[] dims = { vector.errorRate(), vector.uniqueTemplateRatio(), vector.eventRate(),
            vector.p99Latency(), vector.errorBurstScore(), vector.newTemplateRate(),
            vector.traceSpanRatio(), vector.exceptionRate() };
        assertThat(dims).hasSize(8);
        assertThat(Arrays.stream(dims).allMatch(Double::isFinite)).isTrue();
    }

    private static ParsedLogEvent logEvent(String svc, ParsedLogEvent.LogSeverity severity, String templateId) {
        return new ParsedLogEvent(UUID.randomUUID(), UUID.randomUUID(), svc, Instant.now(), severity,
            templateId, "", "", List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), ParsedLogEvent.LogSource.APP);
    }

    private static ParsedLogEvent logEventAt(String svc, Instant ts) {
        return new ParsedLogEvent(UUID.randomUUID(), UUID.randomUUID(), svc, ts, ParsedLogEvent.LogSeverity.INFO,
            "t", "", "", List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), ParsedLogEvent.LogSource.APP);
    }

    private static ParsedLogEvent logEventWithTrace(String svc, String traceId) {
        return new ParsedLogEvent(UUID.randomUUID(), UUID.randomUUID(), svc, Instant.now(), ParsedLogEvent.LogSeverity.INFO,
            "t", "", "", List.of(), Optional.of(traceId), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), ParsedLogEvent.LogSource.APP);
    }

    private static ParsedLogEvent logEventNoTrace(String svc) {
        return new ParsedLogEvent(UUID.randomUUID(), UUID.randomUUID(), svc, Instant.now(), ParsedLogEvent.LogSeverity.INFO,
            "t", "", "", List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), ParsedLogEvent.LogSource.APP);
    }

    private static ParsedLogEvent logEventWithException(String svc, String exceptionClass) {
        return new ParsedLogEvent(UUID.randomUUID(), UUID.randomUUID(), svc, Instant.now(), ParsedLogEvent.LogSeverity.ERROR,
            "t", "", "", List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(exceptionClass), Optional.empty(), ParsedLogEvent.LogSource.APP);
    }

    private static ParsedLogEvent logEventNoException(String svc) {
        return new ParsedLogEvent(UUID.randomUUID(), UUID.randomUUID(), svc, Instant.now(), ParsedLogEvent.LogSeverity.INFO,
            "t", "", "", List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), ParsedLogEvent.LogSource.APP);
    }

    private static List<ParsedLogEvent> generateEventsOverMinutes(String svc, int minutes, int eventsPerMinute) {
        var start = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        var list = new java.util.ArrayList<ParsedLogEvent>();
        for (int m = 0; m < minutes; m++) {
            for (int e = 0; e < eventsPerMinute; e++) {
                list.add(new ParsedLogEvent(UUID.randomUUID(), UUID.randomUUID(), svc,
                    start.plusSeconds(m * 60L + e), ParsedLogEvent.LogSeverity.INFO,
                    "tmpl-" + (e % 5), "", "", List.of(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    ParsedLogEvent.LogSource.APP));
            }
        }
        return list;
    }
}
