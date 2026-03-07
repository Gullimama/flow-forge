# Stage 09 — Log Parser (Java Drain Implementation)

## Goal

Parse raw application logs (Micronaut + Istio Envoy) into structured events with template extraction using a Java implementation of the Drain3 algorithm. Extract log templates, parameters, severity, timestamps, and trace context. Index parsed events into `runtime-events` OpenSearch index.

## Prerequisites

- Stage 06 (log files ingested from Azure Blob)
- Stage 07 (OpenSearch — runtime-events index)

## What to build

### 9.1 Log event model (records)

```java
public record ParsedLogEvent(
    UUID eventId,
    UUID snapshotId,
    String serviceName,
    Instant timestamp,
    LogSeverity severity,
    String templateId,          // Drain cluster ID
    String template,            // e.g., "Failed to connect to <*> on port <*>"
    String rawMessage,
    List<String> parameters,    // Extracted variable parts
    Optional<String> traceId,
    Optional<String> spanId,
    Optional<String> threadName,
    Optional<String> loggerName,
    Optional<String> exceptionClass,
    Optional<String> exceptionMessage,
    LogSource source             // APP, ISTIO_ACCESS, ISTIO_ENVOY
) {
    public enum LogSeverity { TRACE, DEBUG, INFO, WARN, ERROR, FATAL }
    public enum LogSource { APP, ISTIO_ACCESS, ISTIO_ENVOY }
}
```

### 9.2 Drain algorithm (Java implementation)

```java
/**
 * Java implementation of the Drain log parsing algorithm.
 * Based on "Drain: An Online Log Parsing Approach with Fixed Depth Tree"
 * (He et al., 2017).
 *
 * Key design:
 * - Fixed-depth parse tree for O(1) parsing per log line
 * - Similarity threshold for cluster matching
 * - Thread-safe for concurrent use with virtual threads
 */
@Component
public class DrainParser {

    private final double similarityThreshold;
    private final int maxDepth;
    private final int maxChildren;
    private final ConcurrentHashMap<String, LogCluster> clusters = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock treeLock = new ReentrantReadWriteLock();

    public record LogCluster(
        String clusterId,
        List<String> templateTokens,
        AtomicLong matchCount
    ) {
        public String templateString() {
            return String.join(" ", templateTokens);
        }
    }

    /**
     * Parse a single log message → returns the matching cluster.
     */
    public LogCluster parse(String message) {
        var tokens = preprocess(message);

        // First try a read-only match (no mutation)
        treeLock.readLock().lock();
        LogCluster match;
        try {
            match = treeSearch(tokens);
            if (match != null && similarity(match.templateTokens(), tokens) >= similarityThreshold) {
                match.matchCount().incrementAndGet();
                return match;
            }
        } finally {
            treeLock.readLock().unlock();
        }

        // No match or template needs update — acquire write lock
        treeLock.writeLock().lock();
        try {
            // Double-check after acquiring write lock
            match = treeSearch(tokens);
            if (match != null && similarity(match.templateTokens(), tokens) >= similarityThreshold) {
                updateTemplate(match, tokens);
                match.matchCount().incrementAndGet();
                return match;
            }

            // Still no match — create new cluster
            var cluster = new LogCluster(
                generateClusterId(tokens),
                new ArrayList<>(tokens),
                new AtomicLong(1)
            );
            addToTree(cluster);
            clusters.put(cluster.clusterId(), cluster);
            return cluster;
        } finally {
            treeLock.writeLock().unlock();
        }
    }

    private List<String> preprocess(String message) {
        // Tokenize, replace IPs/UUIDs/numbers with <*>
        return Arrays.stream(message.split("\\s+"))
            .map(this::maskToken)
            .toList();
    }

    private String maskToken(String token) {
        if (token.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return "<IP>";
        if (token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return "<UUID>";
        if (token.matches("\\d+")) return "<NUM>";
        if (token.matches("/[\\w/.\\-]+")) return "<PATH>";
        return token;
    }

    /** Get all discovered clusters. */
    public Map<String, LogCluster> getClusters() {
        return Collections.unmodifiableMap(clusters);
    }
}
```

### 9.3 Multi-format log line parser

```java
@Component
public class LogLineParser {

    // Micronaut default log format
    private static final Pattern MICRONAUT_PATTERN = Pattern.compile(
        "^(?<timestamp>\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}\\.?\\d*)\\s+" +
        "(?<level>TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\s+" +
        "\\[(?<thread>[^]]+)]\\s+" +
        "(?<logger>[^\\s]+)\\s*-\\s+" +
        "(?<message>.+)$"
    );

    // Istio access log format
    private static final Pattern ISTIO_ACCESS_PATTERN = Pattern.compile(
        "^\\[(?<timestamp>[^]]+)]\\s+\"(?<method>\\w+)\\s+(?<path>[^\"]+)\\s+[^\"]+\"\\s+" +
        "(?<status>\\d+)\\s+.+$"
    );

    // Istio Envoy log format
    private static final Pattern ISTIO_ENVOY_PATTERN = Pattern.compile(
        "^\\[(?<timestamp>[^]]+)]\\[(?<level>\\w+)]\\[(?<component>[^]]+)]\\s+(?<message>.+)$"
    );

    public record RawLogLine(
        Instant timestamp,
        ParsedLogEvent.LogSeverity severity,
        String message,
        Optional<String> thread,
        Optional<String> logger,
        ParsedLogEvent.LogSource source
    ) {}

    /** Parse a single log line, trying each format in order. */
    public Optional<RawLogLine> parse(String line) {
        return tryMicronaut(line)
            .or(() -> tryIstioAccess(line))
            .or(() -> tryIstioEnvoy(line));
    }

    /** Extract exception info from a stack trace block. */
    public Optional<ExceptionInfo> parseException(List<String> lines) {
        // Look for "Caused by:" or exception class pattern
        for (var line : lines) {
            var m = EXCEPTION_PATTERN.matcher(line);
            if (m.matches()) {
                return Optional.of(new ExceptionInfo(
                    m.group("class"), m.group("message")
                ));
            }
        }
        return Optional.empty();
    }

    public record ExceptionInfo(String exceptionClass, String message) {}
}
```

### 9.4 Trace context extractor

```java
@Component
public class TraceContextExtractor {

    private static final Pattern W3C_TRACEPARENT = Pattern.compile(
        "traceparent[=:]\\s*00-(?<traceId>[0-9a-f]{32})-(?<spanId>[0-9a-f]{16})-\\d{2}"
    );

    private static final Pattern B3_SINGLE = Pattern.compile(
        "[Xx]-[Bb]3[=:]\\s*(?<traceId>[0-9a-f]{16,32})-(?<spanId>[0-9a-f]{16})"
    );

    public record TraceContext(String traceId, String spanId) {}

    /** Extract trace context from a log line or headers. */
    public Optional<TraceContext> extract(String text) {
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
```

### 9.5 Log parsing service (orchestrator)

```java
@Service
public class LogParsingService {

    private final LogLineParser lineParser;
    private final DrainParser drainParser;
    private final TraceContextExtractor traceExtractor;
    private final OpenSearchClientWrapper openSearch;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    /**
     * Parse all log files for a snapshot.
     */
    public LogParseResult parseSnapshotLogs(UUID snapshotId, Path logDir) {
        var logFiles = findLogFiles(logDir);
        var events = new ArrayList<Map<String, Object>>();
        var failedLines = new AtomicInteger(0);

        for (var file : logFiles) {
            var serviceName = inferServiceName(file);
            var lines = readLines(file);

            for (var line : lines) {
                lineParser.parse(line).ifPresentOrElse(
                    raw -> {
                        var cluster = drainParser.parse(raw.message());
                        var trace = traceExtractor.extract(line);
                        var event = buildEvent(snapshotId, serviceName, raw, cluster, trace);
                        events.add(eventToDocument(event));
                    },
                    () -> failedLines.incrementAndGet()
                );
            }
        }

        // Bulk index to OpenSearch
        if (!events.isEmpty()) {
            openSearch.bulkIndex("runtime-events", events);
        }

        // Store Drain clusters as evidence
        var clusterReport = drainParser.getClusters().values().stream()
            .map(c -> Map.of(
                "clusterId", c.clusterId(),
                "template", c.templateString(),
                "matchCount", c.matchCount().get()
            ))
            .toList();
        minio.putJson("evidence", "drain-clusters/" + snapshotId + ".json", clusterReport);

        return new LogParseResult(
            logFiles.size(),
            events.size(),
            failedLines.get(),
            drainParser.getClusters().size()
        );
    }

    private String inferServiceName(Path logFile) {
        // Extract service name from directory structure or filename
        var parts = logFile.toString().split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].endsWith("-service") || parts[i].endsWith("-api")) {
                return parts[i];
            }
        }
        return logFile.getParent().getFileName().toString();
    }
}

public record LogParseResult(int filesProcessed, int eventsIndexed,
                              int failedLines, int uniqueTemplates) {}
```

### 9.6 Dependencies

```kotlin
// libs/log-parser/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    // No external dependencies needed — Drain is implemented in pure Java
    // Log parsing uses JDK regex and standard library
}
```

### AKS Deployment Context

This module is compiled into the FlowForge pipeline runner image (`flowforgeacr.azurecr.io/flowforge-pipeline:latest`) and executes as an Argo Workflow DAG task in the `flowforge` namespace (see Stage 28). It does not require its own Kubernetes Deployment.

**In-cluster service DNS names used:**

| Service | DNS | Port |
|---|---|---|
| PostgreSQL | `flowforge-pg-postgresql.flowforge-infra.svc.cluster.local` | 5432 |
| MinIO | `flowforge-minio.flowforge-infra.svc.cluster.local` | 9000 |
| OpenSearch | `flowforge-opensearch.flowforge-infra.svc.cluster.local` | 9200 |

**Argo task resource class:** CPU (`cpupool` node selector)

---

## Testing & Verification Strategy

### Unit Tests

**`DrainParserTest`** — validate Drain clustering, template extraction, and thread safety.

```java
class DrainParserTest {

    private DrainParser drainParser;

    @BeforeEach
    void setUp() {
        drainParser = new DrainParser(0.5, 4, 100);
    }

    @Test
    void parse_similarMessages_groupsIntoSingleCluster() {
        drainParser.parse("Connected to 10.0.1.5 on port 5432");
        drainParser.parse("Connected to 10.0.1.6 on port 5432");
        drainParser.parse("Connected to 192.168.1.1 on port 3306");

        assertThat(drainParser.getClusters()).hasSize(1);
        var cluster = drainParser.getClusters().values().iterator().next();
        assertThat(cluster.templateString()).contains("<IP>");
        assertThat(cluster.matchCount().get()).isEqualTo(3);
    }

    @Test
    void parse_differentMessages_createsSeparateClusters() {
        drainParser.parse("Connected to 10.0.1.5 on port 5432");
        drainParser.parse("Failed to authenticate user admin");
        drainParser.parse("Request processed in 150ms");

        assertThat(drainParser.getClusters()).hasSize(3);
    }

    @Test
    void parse_uuidMasking_replacesWithPlaceholder() {
        var cluster = drainParser.parse(
                "Processing request a1b2c3d4-e5f6-7890-abcd-ef1234567890");

        assertThat(cluster.templateString()).contains("<UUID>");
        assertThat(cluster.templateString()).doesNotContain("a1b2c3d4");
    }

    @Test
    void parse_numericMasking_replacesNumbers() {
        var cluster = drainParser.parse("Response time 250 ms, status 200");

        assertThat(cluster.templateString()).contains("<NUM>");
    }

    @Test
    void parse_pathMasking_replacesFilePaths() {
        var cluster = drainParser.parse("Loading config from /etc/app/config.yml");

        assertThat(cluster.templateString()).contains("<PATH>");
    }

    @Test
    void parse_threadSafety_noConcurrentModificationException() throws Exception {
        int threadCount = 10;
        int messagesPerThread = 1000;
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < messagesPerThread; i++) {
                        drainParser.parse("Service-%d processed request %d in %dms"
                                .formatted(threadId, i, (int)(Math.random() * 500)));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(drainParser.getClusters()).isNotEmpty();
        long totalMatches = drainParser.getClusters().values().stream()
                .mapToLong(c -> c.matchCount().get()).sum();
        assertThat(totalMatches).isEqualTo(threadCount * messagesPerThread);
    }

    @Test
    void parse_doubleCheckLocking_producesConsistentResults() throws Exception {
        var latch = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        int concurrentParsers = 50;
        var futures = new ArrayList<Future<DrainParser.LogCluster>>();

        for (int i = 0; i < concurrentParsers; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return drainParser.parse("Connection reset by peer 10.0.0.1");
            }));
        }
        latch.countDown();

        var clusterIds = futures.stream()
                .map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })
                .map(DrainParser.LogCluster::clusterId)
                .distinct()
                .toList();

        assertThat(clusterIds).hasSize(1);
    }
}
```

**`LogLineParserTest`** — verify parsing of each log format.

```java
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
        String line = """
                [2024-01-15T10:30:45.000Z] "GET /api/bookings/123 HTTP/1.1" 200 -""";

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
```

**`TraceContextExtractorTest`** — verify W3C and B3 trace context extraction.

```java
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
```

### Integration Tests

**`LogParsingServiceIntegrationTest`** — end-to-end: read log files → parse → Drain cluster → index into OpenSearch.

```java
@SpringBootTest
@Testcontainers
@Tag("integration")
class LogParsingServiceIntegrationTest {

    @Container
    static final OpensearchContainer<?> OPENSEARCH =
            new OpensearchContainer<>("opensearchproject/opensearch:2.18.0")
                    .withSecurityEnabled(false);

    @Container
    static final MinIOContainer MINIO =
            new MinIOContainer("minio/minio:RELEASE.2024-06-13T22-53-53Z");

    @Autowired LogParsingService logParsingService;
    @Autowired OpenSearchClientWrapper openSearch;
    @Autowired MinioStorageClient minio;

    @Test
    void parseSnapshotLogs_indexesEventsToOpenSearch(@TempDir Path logDir) throws Exception {
        copyFixture("sample-micronaut.log",
                logDir.resolve("booking-service/app.log"));
        copyFixture("sample-istio-access.log",
                logDir.resolve("booking-service/istio-proxy.log"));

        var result = logParsingService.parseSnapshotLogs(UUID.randomUUID(), logDir);

        assertThat(result.eventsIndexed()).isGreaterThan(0);
        assertThat(result.uniqueTemplates()).isGreaterThan(0);
        refreshIndex("runtime-events");
        assertThat(openSearch.getDocCount("runtime-events"))
                .isEqualTo(result.eventsIndexed());
    }

    @Test
    void parseSnapshotLogs_storesDrainClustersInMinio(@TempDir Path logDir) throws Exception {
        copyFixture("sample-micronaut.log",
                logDir.resolve("booking-service/app.log"));
        var snapshotId = UUID.randomUUID();

        logParsingService.parseSnapshotLogs(snapshotId, logDir);

        var json = minio.getJson("evidence",
                "drain-clusters/" + snapshotId + ".json");
        assertThat(json).isNotBlank();
    }

    @Test
    void parseSnapshotLogs_serviceNameInferredFromDirectory(@TempDir Path logDir) throws Exception {
        Files.createDirectories(logDir.resolve("payment-service"));
        Files.writeString(logDir.resolve("payment-service/app.log"),
                "2024-01-15T10:30:45.123 INFO [main] com.example.Svc - Started\n");

        var snapshotId = UUID.randomUUID();
        logParsingService.parseSnapshotLogs(snapshotId, logDir);

        refreshIndex("runtime-events");
        var hits = openSearch.multiMatchSearch("runtime-events", "Started",
                List.of("raw_message"), 10);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getSourceAsMap().get("service_name"))
                .isEqualTo("payment-service");
    }
}
```

### Test Fixtures & Sample Data

| Fixture file | Location | Description |
|---|---|---|
| `sample-micronaut.log` | `src/test/resources/` | 200 lines of Micronaut-format log output from a booking service: mix of INFO, WARN, ERROR with stack traces |
| `sample-istio-access.log` | `src/test/resources/` | 100 lines of Istio Envoy access logs with HTTP methods, paths, status codes |
| `sample-istio-envoy.log` | `src/test/resources/` | 50 lines of Istio Envoy internal logs (config, connection events) |
| `sample-mixed-with-traces.log` | `src/test/resources/` | Log lines containing W3C `traceparent` and B3 headers interspersed |
| `sample-multiline-stacktrace.log` | `src/test/resources/` | Multi-line stack trace with `Caused by:` chain |

Example `sample-micronaut.log` content:

```
2024-01-15T10:30:45.001 INFO [main] io.micronaut.runtime.Micronaut - Startup completed in 1423ms
2024-01-15T10:30:46.123 INFO [io-executor-1] com.example.BookingService - Processing booking req-001
2024-01-15T10:30:46.250 DEBUG [io-executor-1] com.example.BookingRepository - Finding booking by ID 12345
2024-01-15T10:30:46.500 WARN [io-executor-2] com.example.PaymentClient - Retry attempt 1 for payment auth
2024-01-15T10:30:47.000 ERROR [io-executor-2] com.example.PaymentClient - Payment authorization failed
java.net.ConnectException: Connection refused
    at java.base/sun.nio.ch.Net.connect0(Native Method)
    at com.example.PaymentClient.authorize(PaymentClient.java:54)
Caused by: java.io.IOException: Network unreachable
    at java.base/java.net.PlainSocketImpl.connect0(Native Method)
```

### Mocking Strategy

| Dependency | Mock or Real | Rationale |
|---|---|---|
| `DrainParser` | **Real** | Core algorithm under test — must validate clustering behavior directly |
| `LogLineParser` | **Real** | Pure regex parsing; no external dependencies |
| `TraceContextExtractor` | **Real** | Pure regex parsing; deterministic and stateless |
| `OpenSearchClientWrapper` | **Real** (Testcontainer) in integration tests; **Mock** in `LogParsingService` unit tests | Isolate parsing logic from indexing concerns |
| `MinioStorageClient` | **Real** (Testcontainer) in integration tests; **Mock** in unit tests | Only needed to verify Drain cluster evidence persistence |
| `MeterRegistry` | **Mock** (`SimpleMeterRegistry`) | Metrics should not affect test outcomes |

### CI/CD Considerations

- **JUnit 5 tags**: `@Tag("unit")` for `DrainParserTest`, `LogLineParserTest`, `TraceContextExtractorTest` (zero containers); `@Tag("integration")` for `LogParsingServiceIntegrationTest`.
- **Pure-Java advantage**: Drain, log line parsing, and trace extraction are pure Java with no external dependencies. Unit tests execute in <2 seconds.
- **Thread safety tests**: The `DrainParser` concurrency test uses virtual threads; ensure CI runs on JDK 21+.
- **Docker requirements**: Integration tests need `opensearchproject/opensearch:2.18.0` and `minio/minio` containers.
- **Sample log fixtures**: Commit sanitized log fixtures to the repository — do not use production log data.
- **Gradle task separation**:
  ```kotlin
  tasks.test { useJUnitPlatform { excludeTags("integration") } }
  tasks.register<Test>("integrationTest") {
      useJUnitPlatform { includeTags("integration") }
  }
  ```
- **Regression**: Add new log format patterns as test cases whenever a new log format is encountered in production data.

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| Micronaut log parse | Feed standard Micronaut log line | Timestamp, level, thread, message extracted |
| Istio access parse | Feed Istio access log | Method, path, status extracted |
| Drain clustering | Feed 100 similar log lines | Grouped into single cluster with template |
| Parameter extraction | "Connected to 10.0.1.5 on port 5432" | template: "Connected to <IP> on port <NUM>" |
| UUID masking | Log with request UUID | UUID replaced with <*> in template |
| Trace context W3C | Log with traceparent header | traceId + spanId extracted |
| Trace context B3 | Log with X-B3 header | traceId + spanId extracted |
| Exception parsing | Multi-line stack trace | Exception class + message extracted |
| Bulk index | 10K log events | All indexed in runtime-events |
| Service inference | Logs from booking-service dir | serviceName = "booking-service" |
| Thread safety | Parse 10K lines with virtual threads | No concurrent modification |
| Cluster persistence | Parse → check evidence bucket | Drain clusters JSON in MinIO |

## Files to create

- `libs/log-parser/build.gradle.kts`
- `libs/log-parser/src/main/java/com/flowforge/logparser/model/ParsedLogEvent.java`
- `libs/log-parser/src/main/java/com/flowforge/logparser/drain/DrainParser.java`
- `libs/log-parser/src/main/java/com/flowforge/logparser/parser/LogLineParser.java`
- `libs/log-parser/src/main/java/com/flowforge/logparser/parser/TraceContextExtractor.java`
- `libs/log-parser/src/main/java/com/flowforge/logparser/service/LogParsingService.java`
- `libs/log-parser/src/test/java/.../DrainParserTest.java`
- `libs/log-parser/src/test/java/.../LogLineParserTest.java`
- `libs/log-parser/src/test/java/.../TraceContextExtractorTest.java`
- `libs/log-parser/src/test/java/.../LogParsingServiceIntegrationTest.java`
- `libs/log-parser/src/test/resources/sample-micronaut.log`
- `libs/log-parser/src/test/resources/sample-istio-access.log`

## Depends on

- Stage 06 (ingested log files)
- Stage 07 (OpenSearch runtime-events index)

## Produces

- Parsed log events indexed in `runtime-events` OpenSearch index
- Drain log template clusters stored in MinIO evidence bucket
- Trace context (W3C/B3) linked to log events for distributed tracing
