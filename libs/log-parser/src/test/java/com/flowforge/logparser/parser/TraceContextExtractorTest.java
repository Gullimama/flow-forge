package com.flowforge.logparser.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TraceContextExtractorTest {

    private final TraceContextExtractor extractor = new TraceContextExtractor();

    @Test
    void extract_w3cTraceparent_extractsTraceAndSpanId() {
        String text = "traceparent=00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        var result = extractor.extract(text);

        assertThat(result).isPresent();
        assertThat(result.get().traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(result.get().spanId()).isEqualTo("00f067aa0ba902b7");
    }

    @Test
    void extract_b3SingleHeader_extractsTraceAndSpanId() {
        String text = "X-B3: 80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1";

        var result = extractor.extract(text);

        assertThat(result).isPresent();
        assertThat(result.get().traceId()).isEqualTo("80f198ee56343ba864fe8b2a57d3eff7");
        assertThat(result.get().spanId()).isEqualTo("e457b5a2e4d86bd1");
    }

    @Test
    void extract_b3With16CharTraceId_extractsCorrectly() {
        String text = "x-b3: 463ac35c9f6413ad-0020000000000001";

        var result = extractor.extract(text);

        assertThat(result).isPresent();
        assertThat(result.get().traceId()).isEqualTo("463ac35c9f6413ad");
    }

    @Test
    void extract_noTraceContext_returnsEmpty() {
        var result = extractor.extract("Just a regular log line with no trace info");

        assertThat(result).isEmpty();
    }

    @Test
    void extract_w3cEmbeddedInLogLine_findsIt() {
        String line = "2024-01-15 INFO Received request traceparent=00-abcdef1234567890abcdef1234567890-1234567890abcdef-01 for /api";

        var result = extractor.extract(line);

        assertThat(result).isPresent();
        assertThat(result.get().traceId()).isEqualTo("abcdef1234567890abcdef1234567890");
    }
}
