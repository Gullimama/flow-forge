package com.flowforge.logparser.parser;

import com.flowforge.logparser.model.ParsedLogEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LogLineParser {

    private static final Pattern MICRONAUT_PATTERN = Pattern.compile(
        "^(?<timestamp>\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}\\.?\\d*)\\s+"
            + "(?<level>TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\s+"
            + "\\[(?<thread>[^]]+)]\\s+"
            + "(?<logger>[^\\s]+)\\s+-\\s+"
            + "(?<message>.+)$"
    );

    private static final Pattern ISTIO_ACCESS_PATTERN = Pattern.compile(
        "^\\[(?<timestamp>[^]]+)]\\s+\"(?<method>\\w+)\\s+(?<path>[^\"]+)\\s+[^\"]+\"\\s+"
            + "(?<status>\\d+)\\s+.+$"
    );

    private static final Pattern ISTIO_ENVOY_PATTERN = Pattern.compile(
        "^\\[(?<timestamp>[^]]+)]\\[(?<level>\\w+)]\\[(?<component>[^]]+)]\\s+(?<message>.+)$"
    );

    private static final Pattern EXCEPTION_PATTERN = Pattern.compile(
        "(?:Caused by:\\s+)?(?<class>[a-zA-Z0-9_.]+):\\s*(?<message>.*)"
    );

    private static final DateTimeFormatter ISO_OR_SPACE = new DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd")
        .optionalStart().appendLiteral("T").optionalEnd()
        .optionalStart().appendLiteral(" ").optionalEnd()
        .appendPattern("HH:mm:ss")
        .optionalStart().appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
        .toFormatter()
        .withZone(ZoneOffset.UTC);

    public record RawLogLine(
        Instant timestamp,
        ParsedLogEvent.LogSeverity severity,
        String message,
        Optional<String> thread,
        Optional<String> logger,
        ParsedLogEvent.LogSource source
    ) {}

    public Optional<RawLogLine> parse(String line) {
        if (line == null || line.isBlank()) return Optional.empty();
        return tryMicronaut(line)
            .or(() -> tryIstioAccess(line))
            .or(() -> tryIstioEnvoy(line));
    }

    private Optional<RawLogLine> tryMicronaut(String line) {
        Matcher m = MICRONAUT_PATTERN.matcher(line);
        if (!m.matches()) return Optional.empty();
        Instant ts = parseTimestamp(m.group("timestamp"));
        ParsedLogEvent.LogSeverity severity = ParsedLogEvent.LogSeverity.valueOf(m.group("level"));
        return Optional.of(new RawLogLine(
            ts,
            severity,
            m.group("message"),
            Optional.of(m.group("thread")),
            Optional.of(m.group("logger")),
            ParsedLogEvent.LogSource.APP
        ));
    }

    private Optional<RawLogLine> tryIstioAccess(String line) {
        Matcher m = ISTIO_ACCESS_PATTERN.matcher(line);
        if (!m.matches()) return Optional.empty();
        Instant ts = parseTimestamp(m.group("timestamp"));
        String msg = m.group("method") + " " + m.group("path") + " -> " + m.group("status");
        return Optional.of(new RawLogLine(
            ts,
            ParsedLogEvent.LogSeverity.INFO,
            msg,
            Optional.empty(),
            Optional.empty(),
            ParsedLogEvent.LogSource.ISTIO_ACCESS
        ));
    }

    private Optional<RawLogLine> tryIstioEnvoy(String line) {
        Matcher m = ISTIO_ENVOY_PATTERN.matcher(line);
        if (!m.matches()) return Optional.empty();
        Instant ts = parseTimestamp(m.group("timestamp"));
        String levelStr = m.group("level").toUpperCase();
        ParsedLogEvent.LogSeverity severity = levelStr.equals("WARNING") ? ParsedLogEvent.LogSeverity.WARN
            : levelStr.equals("ERROR") ? ParsedLogEvent.LogSeverity.ERROR
            : ParsedLogEvent.LogSeverity.INFO;
        String msg = "[" + m.group("component") + "] " + m.group("message");
        return Optional.of(new RawLogLine(
            ts,
            severity,
            msg,
            Optional.empty(),
            Optional.empty(),
            ParsedLogEvent.LogSource.ISTIO_ENVOY
        ));
    }

    private static Instant parseTimestamp(String s) {
        try {
            s = s.replace(" ", "T");
            if (s.length() > 26) s = s.substring(0, 26);
            return Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(s));
        } catch (Exception e) {
            return Instant.now();
        }
    }

    public Optional<ExceptionInfo> parseException(List<String> lines) {
        if (lines == null) return Optional.empty();
        ExceptionInfo last = null;
        for (String line : lines) {
            Matcher m = EXCEPTION_PATTERN.matcher(line.trim());
            if (m.matches()) {
                last = new ExceptionInfo(m.group("class"), m.group("message").trim());
            }
        }
        return Optional.ofNullable(last);
    }

    public record ExceptionInfo(String exceptionClass, String message) {}
}
