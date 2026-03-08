package com.flowforge.logparser.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.logparser.model.ParsedLogEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogLineParserTest {

    private final LogLineParser parser = new LogLineParser();

    @Test
    void parse_micronautLogLine_extractsAllFields() {
        String line = "2024-01-15T10:30:45.123 INFO [main] com.example.BookingService - Booking created successfully";

        var result = parser.parse(line);

        assertThat(result).isPresent();
        var raw = result.get();
        assertThat(raw.severity()).isEqualTo(ParsedLogEvent.LogSeverity.INFO);
        assertThat(raw.thread()).contains("main");
        assertThat(raw.logger()).contains("com.example.BookingService");
        assertThat(raw.message()).isEqualTo("Booking created successfully");
        assertThat(raw.source()).isEqualTo(ParsedLogEvent.LogSource.APP);
    }

    @Test
    void parse_micronautErrorWithTimestamp_parsesCorrectly() {
        String line = "2024-01-15 10:30:45.123 ERROR [io-executor-1] com.example.PaymentGateway - Payment failed for order 12345";

        var result = parser.parse(line);

        assertThat(result).isPresent();
        assertThat(result.get().severity()).isEqualTo(ParsedLogEvent.LogSeverity.ERROR);
    }

    @Test
    void parse_istioAccessLog_extractsMethodAndPath() {
        String line = "[2024-01-15T10:30:45.000Z] \"GET /api/bookings/123 HTTP/1.1\" 200 -";

        var result = parser.parse(line.trim());

        assertThat(result).isPresent();
        assertThat(result.get().source()).isEqualTo(ParsedLogEvent.LogSource.ISTIO_ACCESS);
    }

    @Test
    void parse_istioEnvoyLog_extractsComponentAndLevel() {
        String line = "[2024-01-15T10:30:45.000Z][warning][config] upstream connection timeout";

        var result = parser.parse(line);

        assertThat(result).isPresent();
        assertThat(result.get().source()).isEqualTo(ParsedLogEvent.LogSource.ISTIO_ENVOY);
        assertThat(result.get().severity()).isEqualTo(ParsedLogEvent.LogSeverity.WARN);
    }

    @Test
    void parse_unrecognizedFormat_returnsEmpty() {
        var result = parser.parse("this is not a log line at all");

        assertThat(result).isEmpty();
    }

    @Test
    void parseException_causedBy_extractsClassAndMessage() {
        var lines = List.of(
            "java.lang.RuntimeException: Booking failed",
            "  at com.example.BookingService.create(BookingService.java:42)",
            "Caused by: java.sql.SQLException: Connection refused"
        );

        var result = parser.parseException(lines);

        assertThat(result).isPresent();
        assertThat(result.get().exceptionClass()).isEqualTo("java.sql.SQLException");
        assertThat(result.get().message()).isEqualTo("Connection refused");
    }
}
