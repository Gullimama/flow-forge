package com.flowforge.parser.chunker;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.parser.model.ParsedClass;
import com.flowforge.parser.model.ParsedMethod;
import com.flowforge.parser.model.ReactiveComplexity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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
        // Build a method with ~600 tokens (many short lines) so it exceeds MAX_CHUNK_TOKENS (512)
        var parsed = parsedClassWithLargeMethodManyTokens(600);
        var methodSource = parsed.methods().get(0).rawSource();
        assertThat(chunker.estimateTokens(methodSource))
            .as("method must exceed 512 tokens to trigger split")
            .isGreaterThan(512);

        var chunks = chunker.chunk(parsed);
        var methodChunks = chunks.stream()
            .filter(c -> c.chunkType() == AstAwareChunker.CodeChunk.ChunkType.METHOD)
            .toList();

        assertThat(methodChunks).hasSizeGreaterThan(1);
        methodChunks.forEach(c ->
            assertThat(chunker.estimateTokens(c.content())).isLessThanOrEqualTo(512));
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

    private static ParsedClass parsedClassWith3Methods() {
        var m1 = new ParsedMethod("a", "void", List.of(), List.of(), List.of(),
            false, ReactiveComplexity.NONE, List.of(), Optional.empty(), 5, 8, "public void a() {}");
        var m2 = new ParsedMethod("b", "int", List.of(), List.of(), List.of(),
            false, ReactiveComplexity.NONE, List.of(), Optional.empty(), 10, 12, "public int b() { return 0; }");
        var m3 = new ParsedMethod("c", "String", List.of(), List.of(), List.of(),
            false, ReactiveComplexity.NONE, List.of(), Optional.empty(), 14, 16, "public String c() { return null; }");
        return parsedClassWith(List.of(m1, m2, m3));
    }

    private static ParsedClass parsedClassWithLargeMethod(int lines) {
        var sb = new StringBuilder("public void big() {\n");
        for (int i = 0; i < lines - 2; i++) {
            sb.append("  result = service.findOneByIdAndNameAndStatus(id, name, status).map(this::transform).orElse(null);\n");
        }
        sb.append("}\n");
        var method = new ParsedMethod("big", "void", List.of(), List.of(), List.of(),
            false, ReactiveComplexity.NONE, List.of(), Optional.empty(), 1, lines, sb.toString());
        return parsedClassWith(List.of(method));
    }

    /** Method body with many lines of single tokens so total tokens > 512 and split happens. */
    private static ParsedClass parsedClassWithLargeMethodManyTokens(int targetTokens) {
        var sb = new StringBuilder("public void big() {\n");
        int perLine = 5;
        int lines = (targetTokens / perLine) + 2;
        for (int i = 0; i < lines; i++) {
            sb.append("a b c d e\n");
        }
        sb.append("}\n");
        var method = new ParsedMethod("big", "void", List.of(), List.of(), List.of(),
            false, ReactiveComplexity.NONE, List.of(), Optional.empty(), 1, lines + 2, sb.toString());
        return parsedClassWith(List.of(method));
    }

    private static ParsedClass parsedClassWith(List<ParsedMethod> methods) {
        String raw = "public class Test { " + String.join(" ", methods.stream().map(ParsedMethod::rawSource).toList()) + " }";
        return new ParsedClass(
            "com.example.Test",
            "Test",
            "com.example",
            "",
            ParsedClass.ClassType.CLASS,
            List.of(),
            List.of(),
            Optional.empty(),
            methods,
            List.of(),
            List.of(),
            1,
            100,
            raw
        );
    }
}
