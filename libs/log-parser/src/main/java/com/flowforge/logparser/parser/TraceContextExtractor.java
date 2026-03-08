package com.flowforge.logparser.parser;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TraceContextExtractor {

    private static final Pattern W3C_TRACEPARENT = Pattern.compile(
        "traceparent[=:]\\s*00-(?<traceId>[0-9a-f]{32})-(?<spanId>[0-9a-f]{16})-\\d{2}",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern B3_SINGLE = Pattern.compile(
        "[Xx]-[Bb]3[=:]\\s*(?<traceId>[0-9a-f]{16,32})-(?<spanId>[0-9a-f]{16})",
        Pattern.CASE_INSENSITIVE
    );

    public record TraceContext(String traceId, String spanId) {}

    public Optional<TraceContext> extract(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        var w3c = W3C_TRACEPARENT.matcher(text);
        if (w3c.find()) {
            return Optional.of(new TraceContext(w3c.group("traceId"), w3c.group("spanId")));
        }
        var b3 = B3_SINGLE.matcher(text);
        if (b3.find()) {
            return Optional.of(new TraceContext(b3.group("traceId"), b3.group("spanId")));
        }
        return Optional.empty();
    }
}
