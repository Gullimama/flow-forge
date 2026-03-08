package com.flowforge.parser.service;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.client.OpenSearchClientWrapper;
import com.flowforge.parser.chunker.AstAwareChunker;
import com.flowforge.parser.chunker.AstAwareChunker.CodeChunk;
import com.flowforge.parser.model.ParsedClass;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates parsing of Java files in a snapshot: parse with JavaParser, chunk, index to OpenSearch, store report in MinIO.
 */
@Service
public class CodeParsingService {

    private static final int BATCH_SIZE = 500;

    private final JavaParser javaParser;
    private final MicronautCodeVisitorBridge visitorBridge;
    private final AstAwareChunker chunker;
    private final OpenSearchClientWrapper openSearch;
    private final MinioStorageClient minio;
    private final Counter filesParseCounter;
    private final Counter chunksIndexedCounter;
    private final Timer parseTimer;

    public CodeParsingService(
        JavaParser javaParser,
        MicronautCodeVisitorBridge visitorBridge,
        AstAwareChunker chunker,
        OpenSearchClientWrapper openSearch,
        MinioStorageClient minio,
        MeterRegistry meterRegistry
    ) {
        this.javaParser = javaParser;
        this.visitorBridge = visitorBridge;
        this.chunker = chunker;
        this.openSearch = openSearch;
        this.minio = minio;
        this.filesParseCounter = meterRegistry.counter("flowforge.parser.files.parsed");
        this.chunksIndexedCounter = meterRegistry.counter("flowforge.parser.chunks.indexed");
        this.parseTimer = meterRegistry.timer("flowforge.parser.parse.duration");
    }

    /**
     * Parse all Java files under snapshotDir, chunk, bulk index to code-artifacts, store report in MinIO.
     */
    public CodeParseResult parseSnapshot(UUID snapshotId, Path snapshotDir) {
        List<Path> javaFiles = findJavaFiles(snapshotDir);
        List<Map<String, Object>> allChunks = new ArrayList<>();
        List<String> parseErrors = new ArrayList<>();
        String snapshotDirStr = snapshotDir.toString();

        for (Path file : javaFiles) {
            parseTimer.record(() -> {
                try {
                    ParseResult<CompilationUnit> result = javaParser.parse(
                        ParseStart.COMPILATION_UNIT,
                        Providers.provider(file)
                    );
                    if (result.isSuccessful()) {
                        CompilationUnit cu = result.getResult().orElseThrow();
                        List<ParsedClass> classes = new ArrayList<>();
                        visitorBridge.visit(cu, file, classes);

                        for (ParsedClass clazz : classes) {
                            List<CodeChunk> chunks = chunker.chunk(clazz);
                            String filePathStr = file.toString();
                            String serviceName = deriveServiceName(snapshotDirStr, filePathStr);
                            for (CodeChunk c : chunks) {
                                allChunks.add(chunkToDocument(snapshotId, serviceName, filePathStr, c));
                            }
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

        try {
            for (int i = 0; i < allChunks.size(); i += BATCH_SIZE) {
                List<Map<String, Object>> batch = allChunks.subList(
                    i, Math.min(i + BATCH_SIZE, allChunks.size()));
                openSearch.bulkIndex("code-artifacts", batch);
            }
            chunksIndexedCounter.increment(allChunks.size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        CodeParseReport report = new CodeParseReport(
            snapshotId, javaFiles.size(), allChunks.size(), parseErrors);
        minio.putJson("evidence", parseReportKey(snapshotId), report);

        return new CodeParseResult(
            javaFiles.size(), allChunks.size(), parseErrors.size());
    }

    private static String deriveServiceName(String snapshotDir, String filePath) {
        String relative = filePath.startsWith(snapshotDir)
            ? filePath.substring(snapshotDir.length()).replace('\\', '/')
            : filePath.replace('\\', '/');
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.contains("services/")) {
            int start = relative.indexOf("services/") + "services/".length();
            int end = relative.indexOf('/', start);
            return end > start ? relative.substring(start, end) : relative.substring(start);
        }
        int firstSlash = relative.indexOf('/');
        return firstSlash > 0 ? relative.substring(0, firstSlash) : "default";
    }

    private Map<String, Object> chunkToDocument(
        UUID snapshotId, String serviceName, String filePath, CodeChunk c) {
        return Map.<String, Object>ofEntries(
            Map.entry("snapshot_id", snapshotId.toString()),
            Map.entry("service_name", serviceName),
            Map.entry("file_path", filePath),
            Map.entry("class_fqn", c.classFqn()),
            Map.entry("method_name", c.methodName().orElse("")),
            Map.entry("chunk_type", c.chunkType().name()),
            Map.entry("annotations", c.annotations()),
            Map.entry("content", c.content()),
            Map.entry("content_hash", c.contentHash()),
            Map.entry("reactive_complexity", c.reactiveComplexity().name()),
            Map.entry("line_start", c.lineStart()),
            Map.entry("line_end", c.lineEnd()),
            Map.entry("indexed_at", Instant.now().toString())
        );
    }

    private static String parseReportKey(UUID snapshotId) {
        return "code-parse/" + snapshotId + ".json";
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

    public record CodeParseResult(int filesParsed, int chunksIndexed, int parseErrors) {}
    public record CodeParseReport(UUID snapshotId, int totalFiles, int totalChunks, List<String> errors) {}
}
