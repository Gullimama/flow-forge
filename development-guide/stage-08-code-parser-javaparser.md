# Stage 08 — Java Code Parser (JavaParser AST)

## Goal

Parse every Java source file in the snapshot into a structured AST representation using **JavaParser** — the native Java AST library. Extract classes, methods, fields, annotations, reactive chains, dependency injection points, and HTTP endpoints. Produce semantic chunks suitable for embedding and indexing.

> **Why JavaParser over Tree-sitter?** JavaParser is a native Java library purpose-built for Java source code. It provides full type resolution, semantic analysis, and Java-idiomatic visitors. Since FlowForge analyzes a Java 11/Micronaut estate, JavaParser is the natural and superior choice — no FFI overhead, no language grammar files, and deep understanding of Java constructs including generics, annotations, and lambda expressions.

## Prerequisites

- Stage 05 (snapshot ingest — files on disk)
- Stage 07 (OpenSearch — code-artifacts index)

## What to build

### 8.1 JavaParser configuration

```java
@Configuration
public class JavaParserConfig {

    @Bean
    public ParserConfiguration parserConfiguration(
            @Value("${flowforge.parser.language-level:JAVA_11}") String languageLevel) {
        var config = new ParserConfiguration();
        config.setLanguageLevel(
            ParserConfiguration.LanguageLevel.valueOf(languageLevel));
        config.setAttributeComments(true);
        config.setDoNotAssignCommentsPrecedingEmptyLines(false);
        return config;
    }

    @Bean
    public JavaParser javaParser(ParserConfiguration config) {
        return new JavaParser(config);
    }
}
```

### 8.2 Parsed code model (records)

```java
public record ParsedClass(
    String fqn,
    String simpleName,
    String packageName,
    String filePath,
    ClassType classType,        // CLASS, INTERFACE, ENUM, RECORD, ANNOTATION
    List<String> annotations,
    List<String> implementedInterfaces,
    Optional<String> superClass,
    List<ParsedMethod> methods,
    List<ParsedField> fields,
    List<String> imports,
    int lineStart,
    int lineEnd,
    String rawSource
) {
    public enum ClassType { CLASS, INTERFACE, ENUM, RECORD, ANNOTATION }
}

public record ParsedMethod(
    String name,
    String returnType,
    List<MethodParameter> parameters,
    List<String> annotations,
    List<String> thrownExceptions,
    boolean isReactive,
    ReactiveComplexity reactiveComplexity,
    List<String> httpMethods,           // GET, POST, etc.
    Optional<String> httpPath,
    int lineStart,
    int lineEnd,
    String rawSource
) {}

public record MethodParameter(String name, String type, List<String> annotations) {}

public record ParsedField(
    String name,
    String type,
    List<String> annotations,
    boolean isInjected    // @Inject, @Value, etc.
) {}

public enum ReactiveComplexity { NONE, LINEAR, BRANCHING, COMPLEX }
```

### 8.3 JavaParser visitor

```java
public class MicronautCodeVisitor extends VoidVisitorAdapter<List<ParsedClass>> {

    @Override
    public void visit(ClassOrInterfaceDeclaration n, List<ParsedClass> collector) {
        var methods = n.getMethods().stream()
            .map(this::parseMethod)
            .toList();

        var fields = n.getFields().stream()
            .flatMap(f -> f.getVariables().stream().map(v -> parseField(v, f)))
            .toList();

        var parsed = new ParsedClass(
            resolveFqn(n),
            n.getNameAsString(),
            resolvePackage(n),
            resolveFilePath(n),
            classType(n),
            extractAnnotations(n),
            extractInterfaces(n),
            extractSuperClass(n),
            methods,
            fields,
            extractImports(n),
            n.getBegin().map(p -> p.line).orElse(0),
            n.getEnd().map(p -> p.line).orElse(0),
            n.toString()
        );
        collector.add(parsed);

        super.visit(n, collector);  // Visit inner classes
    }

    private ParsedMethod parseMethod(MethodDeclaration m) {
        var reactiveChain = analyzeReactiveChain(m);
        return new ParsedMethod(
            m.getNameAsString(),
            m.getType().asString(),
            extractParameters(m),
            extractAnnotations(m),
            extractThrown(m),
            reactiveChain != ReactiveComplexity.NONE,
            reactiveChain,
            extractHttpMethods(m),
            extractHttpPath(m),
            m.getBegin().map(p -> p.line).orElse(0),
            m.getEnd().map(p -> p.line).orElse(0),
            m.toString()
        );
    }
}
```

### 8.4 Reactive chain analyzer

```java
@Component
public class ReactiveChainAnalyzer {

    private static final Set<String> REACTIVE_OPERATORS = Set.of(
        "flatMap", "map", "filter", "switchMap", "concatMap",
        "zipWith", "mergeWith", "then", "thenReturn",
        "onErrorResume", "onErrorReturn", "onErrorMap",
        "subscribe", "block", "toFuture",
        "doOnNext", "doOnError", "doOnComplete",
        "publishOn", "subscribeOn", "parallel"
    );

    private static final Set<String> BRANCHING_OPERATORS = Set.of(
        "switchIfEmpty", "switchMap", "zipWith", "mergeWith",
        "onErrorResume", "retry", "retryWhen"
    );

    /** Analyze a method body for reactive chain complexity. */
    public ReactiveComplexity analyze(MethodDeclaration method) {
        var calls = new ArrayList<String>();
        method.walk(MethodCallExpr.class, call -> calls.add(call.getNameAsString()));

        long reactiveCount = calls.stream()
            .filter(REACTIVE_OPERATORS::contains)
            .count();

        if (reactiveCount == 0) return ReactiveComplexity.NONE;

        long branchingCount = calls.stream()
            .filter(BRANCHING_OPERATORS::contains)
            .count();

        if (branchingCount >= 2 || reactiveCount >= 6) return ReactiveComplexity.COMPLEX;
        if (branchingCount >= 1) return ReactiveComplexity.BRANCHING;
        return ReactiveComplexity.LINEAR;
    }
}
```

### 8.5 Micronaut-aware annotation recognizers

```java
@Component
public class MicronautAnnotationRecognizer {

    // Endpoint annotations
    private static final Set<String> HTTP_METHOD_ANNOTATIONS = Set.of(
        "Get", "Post", "Put", "Delete", "Patch", "Options", "Head",
        "HttpMethodMapping"
    );

    // Injection annotations
    private static final Set<String> INJECTION_ANNOTATIONS = Set.of(
        "Inject", "Value", "Property", "Named", "Singleton",
        "Prototype", "RequestScope", "Context"
    );

    // Messaging annotations
    private static final Set<String> MESSAGING_ANNOTATIONS = Set.of(
        "KafkaListener", "KafkaClient", "Topic",
        "RabbitListener", "RabbitClient"
    );

    public boolean isEndpoint(ParsedMethod method) {
        return method.annotations().stream()
            .anyMatch(a -> HTTP_METHOD_ANNOTATIONS.contains(stripPackage(a)));
    }

    public boolean isInjectionPoint(ParsedField field) {
        return field.annotations().stream()
            .anyMatch(a -> INJECTION_ANNOTATIONS.contains(stripPackage(a)));
    }

    public boolean isMessageHandler(ParsedMethod method) {
        return method.annotations().stream()
            .anyMatch(a -> MESSAGING_ANNOTATIONS.contains(stripPackage(a)));
    }
}
```

### 8.6 AST-aware chunker

```java
@Component
public class AstAwareChunker {

    private static final int MAX_CHUNK_TOKENS = 512;
    private static final int OVERLAP_LINES = 3;

    public record CodeChunk(
        String content,
        ChunkType chunkType,
        String classFqn,
        Optional<String> methodName,
        List<String> annotations,
        ReactiveComplexity reactiveComplexity,
        int lineStart,
        int lineEnd,
        String contentHash
    ) {
        public enum ChunkType {
            CLASS_SIGNATURE, METHOD, FIELD_GROUP, INNER_CLASS, IMPORT_BLOCK
        }
    }

    /** Chunk a parsed class into semantic units. */
    public List<CodeChunk> chunk(ParsedClass parsedClass) {
        var chunks = new ArrayList<CodeChunk>();

        // 1. Class signature chunk (annotations + declaration + fields summary)
        chunks.add(buildClassSignatureChunk(parsedClass));

        // 2. Each method as a chunk (split large methods)
        for (var method : parsedClass.methods()) {
            if (estimateTokens(method.rawSource()) <= MAX_CHUNK_TOKENS) {
                chunks.add(buildMethodChunk(parsedClass, method));
            } else {
                chunks.addAll(splitLargeMethod(parsedClass, method));
            }
        }

        return chunks;
    }

    private int estimateTokens(String text) {
        // Java identifiers are longer than English words but often single tokens.
        // Split on whitespace + punctuation for a closer approximation.
        return (int) java.util.regex.Pattern.compile("[\\s{}();,.<>]+")
            .splitAsStream(text)
            .filter(s -> !s.isBlank())
            .count();
    }

    private String contentHash(String content) {
        return Hashing.sha256()
            .hashString(content, StandardCharsets.UTF_8)
            .toString()
            .substring(0, 16);
    }
}
```

### 8.7 Code parsing service (orchestrator)

```java
@Service
public class CodeParsingService {

    private final JavaParser javaParser;
    private final MicronautCodeVisitor visitor;
    private final ReactiveChainAnalyzer reactiveAnalyzer;
    private final AstAwareChunker chunker;
    private final OpenSearchClientWrapper openSearch;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    private final Counter filesParseCounter;
    private final Counter chunksIndexedCounter;
    private final Timer parseTimer;

    /**
     * Parse all Java files in a snapshot and index the chunks.
     */
    public CodeParseResult parseSnapshot(UUID snapshotId, Path snapshotDir) {
        var javaFiles = findJavaFiles(snapshotDir);
        var allChunks = new ArrayList<Map<String, Object>>();
        var parseErrors = new ArrayList<String>();

        for (var file : javaFiles) {
            parseTimer.record(() -> {
                try {
                    var result = javaParser.parse(file);
                    if (result.isSuccessful()) {
                        var cu = result.getResult().orElseThrow();
                        var classes = new ArrayList<ParsedClass>();
                        cu.accept(visitor, classes);

                        for (var clazz : classes) {
                            var chunks = chunker.chunk(clazz);
                            chunks.forEach(c -> allChunks.add(chunkToDocument(snapshotId, file, c)));
                        }
                        filesParseCounter.increment();
                    } else {
                        parseErrors.add(file + ": " + result.getProblems());
                    }
                } catch (Exception e) {
                    parseErrors.add(file + ": " + e.getMessage());
                }
            });
        }

        // Bulk index to OpenSearch in batches to limit memory pressure
        int batchSize = 500;
        for (int i = 0; i < allChunks.size(); i += batchSize) {
            var batch = allChunks.subList(i, Math.min(i + batchSize, allChunks.size()));
            openSearch.bulkIndex("code-artifacts", batch);
        }
        chunksIndexedCounter.increment(allChunks.size());

        // Store parse report in MinIO
        var report = new CodeParseReport(snapshotId, javaFiles.size(),
            allChunks.size(), parseErrors);
        minio.putJson("evidence", parseReportKey(snapshotId), report);

        return new CodeParseResult(javaFiles.size(), allChunks.size(), parseErrors.size());
    }

    private List<Path> findJavaFiles(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("/src/test/"))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

public record CodeParseResult(int filesParsed, int chunksIndexed, int parseErrors) {}
public record CodeParseReport(UUID snapshotId, int totalFiles, int totalChunks, List<String> errors) {}
```

### 8.8 Dependencies

```kotlin
// libs/code-parser/build.gradle.kts
dependencies {
    implementation(libs.javaparser.core)           // com.github.javaparser:javaparser-core:3.26.3
    implementation(libs.javaparser.symbolsolver)   // com.github.javaparser:javaparser-symbol-solver-core:3.26.3
    implementation(libs.guava)                      // for Hashing utility
    implementation(project(":libs:common"))
}
```

Add to version catalog:
```toml
[versions]
javaparser = "3.26.3"

[libraries]
javaparser-core = { module = "com.github.javaparser:javaparser-core", version.ref = "javaparser" }
javaparser-symbolsolver = { module = "com.github.javaparser:javaparser-symbol-solver-core", version.ref = "javaparser" }
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

**`MicronautCodeVisitorTest`** — validate AST extraction from Java source files.

```java
class MicronautCodeVisitorTest {

    private final JavaParser javaParser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(
                    ParserConfiguration.LanguageLevel.JAVA_11));

    private final MicronautCodeVisitor visitor = new MicronautCodeVisitor();

    @Test
    void visit_micronautController_extractsClassAndMethods() {
        String source = """
                package com.example;

                import io.micronaut.http.annotation.*;

                @Controller("/bookings")
                public class BookingController {
                    @Get("/{id}")
                    public Booking getBooking(String id) { return null; }

                    @Post
                    public Booking createBooking(@Body BookingRequest req) { return null; }
                }
                """;

        var classes = parseSource(source);

        assertThat(classes).hasSize(1);
        var clazz = classes.get(0);
        assertThat(clazz.fqn()).isEqualTo("com.example.BookingController");
        assertThat(clazz.annotations()).contains("Controller");
        assertThat(clazz.methods()).hasSize(2);
    }

    @Test
    void visit_extractsHttpMethodAndPath() {
        String source = """
                package com.example;
                import io.micronaut.http.annotation.*;

                @Controller("/api")
                public class ApiController {
                    @Get("/bookings/{id}")
                    public String get(String id) { return ""; }
                }
                """;

        var classes = parseSource(source);
        var method = classes.get(0).methods().get(0);

        assertThat(method.httpMethods()).containsExactly("GET");
        assertThat(method.httpPath()).contains("/bookings/{id}");
    }

    @Test
    void visit_innerClass_extractedSeparately() {
        String source = """
                package com.example;
                public class Outer {
                    public static class Inner {
                        public void doWork() {}
                    }
                }
                """;

        var classes = parseSource(source);

        assertThat(classes).hasSize(2);
        assertThat(classes).extracting(ParsedClass::simpleName)
                .containsExactlyInAnyOrder("Outer", "Inner");
    }

    @Test
    void visit_enumWithMethods_extractedCorrectly() {
        String source = """
                package com.example;
                public enum Status {
                    ACTIVE, INACTIVE;
                    public boolean isActive() { return this == ACTIVE; }
                }
                """;

        var classes = parseSource(source);

        assertThat(classes.get(0).classType()).isEqualTo(ParsedClass.ClassType.ENUM);
        assertThat(classes.get(0).methods()).hasSize(1);
    }

    @Test
    void visit_injectedFields_markedAsInjected() {
        String source = """
                package com.example;
                import jakarta.inject.Inject;
                public class MyService {
                    @Inject BookingRepository repository;
                    private String name;
                }
                """;

        var classes = parseSource(source);
        var fields = classes.get(0).fields();

        assertThat(fields).hasSize(2);
        assertThat(fields).filteredOn(ParsedField::isInjected).hasSize(1);
        assertThat(fields).filteredOn(ParsedField::isInjected)
                .extracting(ParsedField::name).containsExactly("repository");
    }

    private List<ParsedClass> parseSource(String source) {
        var result = javaParser.parse(source);
        assertThat(result.isSuccessful()).isTrue();
        var classes = new ArrayList<ParsedClass>();
        result.getResult().orElseThrow().accept(visitor, classes);
        return classes;
    }
}
```

**`ReactiveChainAnalyzerTest`** — verify complexity detection at each level.

```java
class ReactiveChainAnalyzerTest {

    private final ReactiveChainAnalyzer analyzer = new ReactiveChainAnalyzer();

    @Test
    void analyze_noReactiveOperators_returnsNone() {
        var method = parseMethod("""
                public String getName() { return this.name; }
                """);

        assertThat(analyzer.analyze(method)).isEqualTo(ReactiveComplexity.NONE);
    }

    @Test
    void analyze_linearChain_returnsLinear() {
        var method = parseMethod("""
                public Mono<String> fetch() {
                    return repository.findById(id)
                            .map(Entity::getName)
                            .filter(n -> !n.isBlank());
                }
                """);

        assertThat(analyzer.analyze(method)).isEqualTo(ReactiveComplexity.LINEAR);
    }

    @Test
    void analyze_branchingChain_returnsBranching() {
        var method = parseMethod("""
                public Mono<String> fetch() {
                    return repository.findById(id)
                            .flatMap(this::transform)
                            .switchIfEmpty(Mono.just("default"))
                            .map(String::toUpperCase);
                }
                """);

        assertThat(analyzer.analyze(method)).isEqualTo(ReactiveComplexity.BRANCHING);
    }

    @Test
    void analyze_complexChain_returnsComplex() {
        var method = parseMethod("""
                public Mono<Result> process() {
                    return repo.findById(id)
                            .flatMap(this::validate)
                            .switchIfEmpty(createNew())
                            .zipWith(configService.getConfig())
                            .flatMap(t -> transform(t.getT1(), t.getT2()))
                            .onErrorResume(this::handleError)
                            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)));
                }
                """);

        assertThat(analyzer.analyze(method)).isEqualTo(ReactiveComplexity.COMPLEX);
    }
}
```

**`AstAwareChunkerTest`** — validate chunking boundaries and token limits.

```java
class AstAwareChunkerTest {

    private final AstAwareChunker chunker = new AstAwareChunker();

    @Test
    void chunk_smallClass_producesSignatureAndMethodChunks() {
        var parsed = parsedClassWith3Methods();

        var chunks = chunker.chunk(parsed);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(4); // 1 signature + 3 methods
        assertThat(chunks.get(0).chunkType())
                .isEqualTo(AstAwareChunker.CodeChunk.ChunkType.CLASS_SIGNATURE);
        assertThat(chunks).filteredOn(c ->
                c.chunkType() == AstAwareChunker.CodeChunk.ChunkType.METHOD).hasSize(3);
    }

    @Test
    void chunk_largeMethod_splitIntoOverlappingChunks() {
        var parsed = parsedClassWithLargeMethod(150); // 150 lines

        var chunks = chunker.chunk(parsed);
        var methodChunks = chunks.stream()
                .filter(c -> c.chunkType() == AstAwareChunker.CodeChunk.ChunkType.METHOD)
                .toList();

        assertThat(methodChunks.size()).isGreaterThan(1);
        methodChunks.forEach(c ->
                assertThat(estimateTokens(c.content())).isLessThanOrEqualTo(512));
    }

    @Test
    void chunk_contentHash_isDeterministic() {
        var parsed = parsedClassWith3Methods();

        var chunks1 = chunker.chunk(parsed);
        var chunks2 = chunker.chunk(parsed);

        assertThat(chunks1).hasSameSizeAs(chunks2);
        for (int i = 0; i < chunks1.size(); i++) {
            assertThat(chunks1.get(i).contentHash()).isEqualTo(chunks2.get(i).contentHash());
        }
    }

    @Test
    void chunk_preservesAnnotationsOnMethodChunks() {
        var method = new ParsedMethod("handleGet", "Mono<Response>",
                List.of(), List.of("Get", "ExecuteOn"), List.of(),
                true, ReactiveComplexity.LINEAR,
                List.of("GET"), Optional.of("/bookings"), 10, 25, "source");
        var parsed = parsedClassWith(List.of(method));

        var chunks = chunker.chunk(parsed);
        var methodChunk = chunks.stream()
                .filter(c -> c.methodName().isPresent())
                .findFirst().orElseThrow();

        assertThat(methodChunk.annotations()).contains("Get", "ExecuteOn");
    }
}
```

**`MicronautAnnotationRecognizerTest`** — verify annotation classification.

```java
class MicronautAnnotationRecognizerTest {

    private final MicronautAnnotationRecognizer recognizer = new MicronautAnnotationRecognizer();

    @Test
    void isEndpoint_httpAnnotation_returnsTrue() {
        var method = new ParsedMethod("get", "String", List.of(),
                List.of("io.micronaut.http.annotation.Get"), List.of(),
                false, ReactiveComplexity.NONE, List.of("GET"), Optional.empty(), 1, 5, "");

        assertThat(recognizer.isEndpoint(method)).isTrue();
    }

    @Test
    void isInjectionPoint_injectAnnotation_returnsTrue() {
        var field = new ParsedField("repo", "BookingRepository",
                List.of("jakarta.inject.Inject"), true);

        assertThat(recognizer.isInjectionPoint(field)).isTrue();
    }

    @Test
    void isMessageHandler_kafkaListener_returnsTrue() {
        var method = new ParsedMethod("onMessage", "void", List.of(),
                List.of("io.micronaut.configuration.kafka.annotation.KafkaListener"),
                List.of(), false, ReactiveComplexity.NONE, List.of(), Optional.empty(), 1, 5, "");

        assertThat(recognizer.isMessageHandler(method)).isTrue();
    }

    @Test
    void isEndpoint_nonHttpAnnotation_returnsFalse() {
        var method = new ParsedMethod("process", "void", List.of(),
                List.of("Singleton"), List.of(),
                false, ReactiveComplexity.NONE, List.of(), Optional.empty(), 1, 5, "");

        assertThat(recognizer.isEndpoint(method)).isFalse();
    }
}
```

### Integration Tests

**`CodeParsingServiceIntegrationTest`** — end-to-end: parse real Java files → chunk → index into OpenSearch.

```java
@SpringBootTest
@Testcontainers
@Tag("integration")
class CodeParsingServiceIntegrationTest {

    @Container
    static final OpensearchContainer<?> OPENSEARCH =
            new OpensearchContainer<>("opensearchproject/opensearch:2.18.0")
                    .withSecurityEnabled(false);

    @Container
    static final MinIOContainer MINIO =
            new MinIOContainer("minio/minio:RELEASE.2024-06-13T22-53-53Z");

    @Autowired CodeParsingService codeParsingService;
    @Autowired OpenSearchClientWrapper openSearch;

    @Test
    void parseSnapshot_indexesChunksToOpenSearch(@TempDir Path snapshotDir) throws Exception {
        copyFixture("sample-booking-controller.java",
                snapshotDir.resolve("src/main/java/com/example/BookingController.java"));
        copyFixture("sample-payment-service.java",
                snapshotDir.resolve("src/main/java/com/example/PaymentService.java"));

        var result = codeParsingService.parseSnapshot(UUID.randomUUID(), snapshotDir);

        assertThat(result.filesParsed()).isEqualTo(2);
        assertThat(result.chunksIndexed()).isGreaterThan(0);
        assertThat(result.parseErrors()).isZero();

        refreshIndex("code-artifacts");
        assertThat(openSearch.getDocCount("code-artifacts"))
                .isEqualTo(result.chunksIndexed());
    }

    @Test
    void parseSnapshot_malformedFile_capturesErrorAndContinues(@TempDir Path snapshotDir)
            throws Exception {
        copyFixture("sample-booking-controller.java",
                snapshotDir.resolve("src/main/java/com/example/BookingController.java"));
        Files.writeString(
                snapshotDir.resolve("src/main/java/com/example/Broken.java"),
                "public class { this is not valid java }}}");

        var result = codeParsingService.parseSnapshot(UUID.randomUUID(), snapshotDir);

        assertThat(result.filesParsed()).isEqualTo(1);
        assertThat(result.parseErrors()).isEqualTo(1);
    }

    @Test
    void parseSnapshot_batchIndexingRespectsBatchSize(@TempDir Path snapshotDir) throws Exception {
        for (int i = 0; i < 60; i++) {
            Files.writeString(
                    snapshotDir.resolve("src/main/java/com/example/Svc" + i + ".java"),
                    "package com.example;\npublic class Svc" + i +
                            " {\n  public void run() {}\n}");
        }

        var result = codeParsingService.parseSnapshot(UUID.randomUUID(), snapshotDir);

        assertThat(result.filesParsed()).isEqualTo(60);
        assertThat(result.chunksIndexed()).isGreaterThanOrEqualTo(60);
    }
}
```

### Test Fixtures & Sample Data

| Fixture file | Location | Description |
|---|---|---|
| `sample-booking-controller.java` | `src/test/resources/fixtures/` | Micronaut `@Controller` with `@Get`, `@Post`, reactive return types, and `@Inject` fields |
| `sample-payment-service.java` | `src/test/resources/fixtures/` | Service class with complex reactive chain (`flatMap`, `switchIfEmpty`, `zipWith`) |
| `sample-kafka-listener.java` | `src/test/resources/fixtures/` | `@KafkaListener` class with `@Topic` methods |
| `sample-enum.java` | `src/test/resources/fixtures/` | Java enum with methods for enum parsing tests |
| `sample-inner-classes.java` | `src/test/resources/fixtures/` | Class with nested static and anonymous inner classes |
| `sample-large-method.java` | `src/test/resources/fixtures/` | Class with a 150-line method to test chunk splitting |

Example fixture content for `sample-booking-controller.java`:

```java
package com.example.booking;

import io.micronaut.http.annotation.*;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Inject;
import reactor.core.publisher.Mono;

@Controller("/bookings")
public class BookingController {

    @Inject BookingRepository repository;
    @Inject PaymentClient paymentClient;

    @Get("/{id}")
    public Mono<Booking> getBooking(String id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NotFoundException("Booking not found")));
    }

    @Post
    public Mono<HttpResponse<Booking>> createBooking(@Body BookingRequest request) {
        return repository.save(new Booking(request))
                .flatMap(b -> paymentClient.authorize(b.id(), request.amount())
                        .thenReturn(b))
                .map(HttpResponse::created);
    }
}
```

### Mocking Strategy

| Dependency | Mock or Real | Rationale |
|---|---|---|
| `JavaParser` | **Real** | Core of the stage — always parse real Java source |
| `MicronautCodeVisitor` | **Real** | Pure AST traversal; no external dependencies |
| `ReactiveChainAnalyzer` | **Real** | Stateless analysis of method call expressions |
| `AstAwareChunker` | **Real** | Pure transformation logic with deterministic output |
| `MicronautAnnotationRecognizer` | **Real** | Simple set-based lookups; no reason to mock |
| `OpenSearchClientWrapper` | **Real** (Testcontainer) in integration tests; **Mock** in `CodeParsingService` unit tests | Unit tests should isolate parsing from indexing |
| `MinioStorageClient` | **Real** (Testcontainer) in integration tests; **Mock** in unit tests | Only needed for evidence storage verification |
| `MeterRegistry` | **Mock** (`SimpleMeterRegistry`) | Metrics should not affect test outcomes |

### CI/CD Considerations

- **JUnit 5 tags**: `@Tag("unit")` for visitor/analyzer/chunker tests (no containers needed), `@Tag("integration")` for `CodeParsingServiceIntegrationTest`.
- **Test separation**: Unit tests for `MicronautCodeVisitor`, `ReactiveChainAnalyzer`, `AstAwareChunker`, and `MicronautAnnotationRecognizer` require zero Docker containers — they are pure Java logic.
- **Docker requirements**: Integration tests need `opensearchproject/opensearch:2.18.0` and `minio/minio` containers.
- **Fixture management**: Store sample Java source files in `src/test/resources/fixtures/` and load via classloader. Do not hard-code Java source strings in integration tests.
- **Gradle task separation**:
  ```kotlin
  tasks.test { useJUnitPlatform { excludeTags("integration") } }
  tasks.register<Test>("integrationTest") {
      useJUnitPlatform { includeTags("integration") }
  }
  ```
- **Performance**: Parsing 50+ Java files takes <5 seconds; set integration test timeout to 60 seconds to account for OpenSearch container startup.

## Verification

| Check | How to verify | Pass criteria |
|---|---|---|
| Parse single file | Parse a Micronaut controller | Extracts class, methods, annotations |
| FQN resolution | Parse class in package | Full `com.example.BookingController` |
| Reactive detection | Parse method with `flatMap.switchIfEmpty.map` | `BRANCHING` complexity |
| Complex reactive | Parse method with 6+ operators, 2+ branches | `COMPLEX` complexity |
| Endpoint detection | Parse `@Get("/bookings/{id}")` | httpMethods=["GET"], httpPath="/bookings/{id}" |
| Injection detection | Parse `@Inject BookingRepository repo` | isInjected=true |
| Chunking | Parse 200-line class | Methods as individual chunks, class signature chunk |
| Large method split | Parse 100-line method | Split into overlapping chunks ≤ 512 tokens |
| Content hash | Same source → same hash | Deterministic SHA-256 prefix |
| Bulk index | Parse snapshot of 50 files | All chunks in code-artifacts index |
| Parse errors | Feed malformed Java | Error captured, not thrown |
| Inner classes | Parse file with inner/anonymous classes | All classes extracted |
| Enum parsing | Parse Java enum with methods | Enum type + methods extracted |
| Kafka handler | Parse `@KafkaListener` method | isMessageHandler=true |

## Files to create

- `libs/code-parser/build.gradle.kts`
- `libs/code-parser/src/main/java/com/flowforge/parser/config/JavaParserConfig.java`
- `libs/code-parser/src/main/java/com/flowforge/parser/model/ParsedClass.java`
- `libs/code-parser/src/main/java/com/flowforge/parser/model/ParsedMethod.java`
- `libs/code-parser/src/main/java/com/flowforge/parser/model/ParsedField.java`
- `libs/code-parser/src/main/java/com/flowforge/parser/model/MethodParameter.java`
- `libs/code-parser/src/main/java/com/flowforge/parser/model/ReactiveComplexity.java`
- `libs/code-parser/src/main/java/com/flowforge/parser/model/CodeChunk.java`
- `libs/code-parser/src/main/java/com/flowforge/parser/visitor/MicronautCodeVisitor.java`
- `libs/code-parser/src/main/java/com/flowforge/parser/analysis/ReactiveChainAnalyzer.java`
- `libs/code-parser/src/main/java/com/flowforge/parser/analysis/MicronautAnnotationRecognizer.java`
- `libs/code-parser/src/main/java/com/flowforge/parser/chunker/AstAwareChunker.java`
- `libs/code-parser/src/main/java/com/flowforge/parser/service/CodeParsingService.java`
- `libs/code-parser/src/test/java/.../MicronautCodeVisitorTest.java`
- `libs/code-parser/src/test/java/.../ReactiveChainAnalyzerTest.java`
- `libs/code-parser/src/test/java/.../AstAwareChunkerTest.java`
- `libs/code-parser/src/test/java/.../CodeParsingServiceIntegrationTest.java`

## Depends on

- Stage 05 (snapshot files)
- Stage 07 (OpenSearch code-artifacts index)

## Produces

- Per-snapshot parsed AST data stored in MinIO evidence bucket
- Code chunks indexed in `code-artifacts` OpenSearch index
- Reactive complexity annotations on every method
- Micronaut annotation recognition (endpoints, injection, messaging)
