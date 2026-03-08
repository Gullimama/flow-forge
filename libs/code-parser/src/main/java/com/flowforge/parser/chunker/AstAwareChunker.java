package com.flowforge.parser.chunker;

import com.flowforge.parser.model.ParsedClass;
import com.flowforge.parser.model.ParsedMethod;
import com.flowforge.parser.model.ReactiveComplexity;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Chunks a parsed class into semantic units (class signature, methods); splits large methods.
 */
@Component
public class AstAwareChunker {

    private static final int MAX_CHUNK_TOKENS = 512;
    private static final int OVERLAP_LINES = 3;
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s{}();,.<>]+");

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
            CLASS_SIGNATURE,
            METHOD,
            FIELD_GROUP,
            INNER_CLASS,
            IMPORT_BLOCK
        }
    }

    /**
     * Chunk a parsed class into semantic units.
     */
    public List<CodeChunk> chunk(ParsedClass parsedClass) {
        var chunks = new ArrayList<CodeChunk>();

        chunks.add(buildClassSignatureChunk(parsedClass));

        for (ParsedMethod method : parsedClass.methods()) {
            if (estimateTokens(method.rawSource()) <= MAX_CHUNK_TOKENS) {
                chunks.add(buildMethodChunk(parsedClass, method));
            } else {
                chunks.addAll(splitLargeMethod(parsedClass, method));
            }
        }

        return chunks;
    }

    int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) TOKEN_SPLIT.splitAsStream(text).filter(s -> !s.isBlank()).count();
    }

    private String contentHash(String content) {
        return Hashing.sha256()
            .hashString(content, StandardCharsets.UTF_8)
            .toString()
            .substring(0, 16);
    }

    private CodeChunk buildClassSignatureChunk(ParsedClass parsedClass) {
        String raw = parsedClass.rawSource();
        int maxLen = Math.min(raw.length(), 2000);
        String content = maxLen < raw.length() ? raw.substring(0, maxLen) + "\n..." : raw;
        return new CodeChunk(
            content,
            CodeChunk.ChunkType.CLASS_SIGNATURE,
            parsedClass.fqn(),
            Optional.empty(),
            parsedClass.annotations(),
            ReactiveComplexity.NONE,
            parsedClass.lineStart(),
            parsedClass.lineEnd(),
            contentHash(content)
        );
    }

    private CodeChunk buildMethodChunk(ParsedClass parsedClass, ParsedMethod method) {
        return new CodeChunk(
            method.rawSource(),
            CodeChunk.ChunkType.METHOD,
            parsedClass.fqn(),
            Optional.of(method.name()),
            method.annotations(),
            method.reactiveComplexity(),
            method.lineStart(),
            method.lineEnd(),
            contentHash(method.rawSource())
        );
    }

    private List<CodeChunk> splitLargeMethod(ParsedClass parsedClass, ParsedMethod method) {
        String[] lines = method.rawSource().split("\n");
        List<CodeChunk> result = new ArrayList<>();
        int start = 0;
        while (start < lines.length) {
            var chunkLines = new ArrayList<String>();
            int tokens = 0;
            int end = start;
            for (int i = start; i < lines.length; i++) {
                int lineTokens = estimateTokens(lines[i]);
                if (tokens + lineTokens > MAX_CHUNK_TOKENS && !chunkLines.isEmpty()) {
                    break;
                }
                chunkLines.add(lines[i]);
                tokens += lineTokens;
                end = i + 1;
            }
            if (chunkLines.isEmpty()) {
                break;
            }
            String content = String.join("\n", chunkLines);
            result.add(new CodeChunk(
                content,
                CodeChunk.ChunkType.METHOD,
                parsedClass.fqn(),
                Optional.of(method.name()),
                method.annotations(),
                method.reactiveComplexity(),
                method.lineStart() + start,
                method.lineStart() + end - 1,
                contentHash(content)
            ));
            start = Math.max(end - OVERLAP_LINES, end);
            if (start >= lines.length) {
                break;
            }
        }
        return result;
    }
}
