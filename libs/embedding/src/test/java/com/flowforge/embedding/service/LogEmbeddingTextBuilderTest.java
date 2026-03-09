package com.flowforge.embedding.service;

import com.flowforge.logparser.model.ParsedLogEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogEmbeddingTextBuilderTest {

    private final LogEmbeddingTextBuilder builder = new LogEmbeddingTextBuilder();

    @Test
    void passageText_startsWithPassagePrefix() {
        var event = LogEmbeddingTestFixtures.parsedLogEvent("booking-service", ParsedLogEvent.LogSeverity.ERROR,
            "Failed to connect to <*> on port <*>");
        var text = builder.buildPassageText(event);
        assertThat(text).startsWith("passage: ");
    }

    @Test
    void passageText_containsServiceNameInBrackets() {
        var event = LogEmbeddingTestFixtures.parsedLogEvent("payment-service", ParsedLogEvent.LogSeverity.INFO,
            "Processing payment");
        var text = builder.buildPassageText(event);
        assertThat(text).contains("[payment-service]");
    }

    @Test
    void passageText_includesSeverityAndTemplate() {
        var event = LogEmbeddingTestFixtures.parsedLogEvent("booking-service", ParsedLogEvent.LogSeverity.ERROR,
            "Connection refused to host <*>");
        var text = builder.buildPassageText(event);
        assertThat(text).contains("ERROR: Connection refused to host <*>");
    }

    @Test
    void passageText_appendsExceptionInfoWhenPresent() {
        var event = LogEmbeddingTestFixtures.parsedLogEventWithException("order-service",
            ParsedLogEvent.LogSeverity.ERROR, "Request failed",
            "ConnectionRefusedException", "Connection timed out after 5000ms");
        var text = builder.buildPassageText(event);
        assertThat(text)
            .contains("| exception: ConnectionRefusedException")
            .contains("- Connection timed out after 5000ms");
    }

    @Test
    void passageText_truncatesLongExceptionMessages() {
        var longMsg = "X".repeat(500);
        var event = LogEmbeddingTestFixtures.parsedLogEventWithException("svc", ParsedLogEvent.LogSeverity.ERROR,
            "Boom", "RuntimeException", longMsg);
        var text = builder.buildPassageText(event);
        assertThat(text).contains("...");
        assertThat(text.length()).isLessThanOrEqualTo(250 + 200 + 10);
    }

    @Test
    void queryText_startsWithQueryPrefix() {
        var text = builder.buildQueryText("connection timeout database");
        assertThat(text).isEqualTo("query: connection timeout database");
    }

    @Test
    void templateText_containsServiceAndFrequency() {
        var cluster = LogEmbeddingTestFixtures.drainCluster("CL-001", "Failed to connect to <*>", 42);
        var text = builder.buildTemplateText("booking-service", cluster);
        assertThat(text)
            .startsWith("passage: ")
            .contains("[booking-service]")
            .contains("Log template:")
            .contains("(frequency: 42)");
    }

    @Test
    void templateText_storedCluster_containsServiceAndFrequency() {
        var cluster = LogEmbeddingTestFixtures.storedDrainCluster("CL-001", "Failed to connect to <*>", 42);
        var text = builder.buildTemplateText("booking-service", cluster);
        assertThat(text)
            .startsWith("passage: ")
            .contains("[booking-service]")
            .contains("Log template:")
            .contains("Failed to connect to <*>")
            .contains("(frequency: 42)");
    }
}
