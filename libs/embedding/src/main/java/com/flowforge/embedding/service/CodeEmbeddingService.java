package com.flowforge.embedding.service;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.client.OpenSearchClientWrapper;
import com.flowforge.vectorstore.service.VectorStoreService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class CodeEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(CodeEmbeddingService.class);
    private static final int BATCH_SIZE = 64;
    private static final int PAGE_SIZE = 5000;

    private final VectorStoreService vectorStoreService;
    private final OpenSearchClientWrapper openSearch;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;
    private final EmbeddingModel codeEmbeddingModel;

    public CodeEmbeddingService(
            VectorStoreService vectorStoreService,
            OpenSearchClientWrapper openSearch,
            MinioStorageClient minio,
            MeterRegistry meterRegistry,
            @Qualifier("codeEmbeddingModel") EmbeddingModel codeEmbeddingModel) {
        this.vectorStoreService = vectorStoreService;
        this.openSearch = openSearch;
        this.minio = minio;
        this.meterRegistry = meterRegistry;
        this.codeEmbeddingModel = codeEmbeddingModel;
    }

    /**
     * Embed all code chunks for a snapshot and store in Qdrant.
     */
    public CodeEmbeddingResult embedSnapshot(UUID snapshotId) {
        List<Map<String, Object>> chunks;
        try {
            chunks = fetchCodeChunks(snapshotId);
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch code chunks for snapshot " + snapshotId, e);
        }
        log.info("Embedding {} code chunks for snapshot {}", chunks.size(), snapshotId);

        List<Document> documents = chunks.stream()
            .map(chunk -> buildDocument(snapshotId, chunk))
            .toList();

        for (List<Document> batch : partition(documents, BATCH_SIZE)) {
            Timer.Sample sample = Timer.start(meterRegistry);
            vectorStoreService.addCodeDocuments(batch);
            sample.stop(meterRegistry.timer("flowforge.embedding.code.batch"));
        }

        int dimensions = codeEmbeddingModel.dimensions();
        EmbeddingStats stats = new EmbeddingStats(snapshotId, documents.size(), dimensions, "code-embedding");
        minio.putJson("evidence", "embeddings/code/" + snapshotId + ".json", stats);

        meterRegistry.counter("flowforge.embedding.code.total").increment(documents.size());

        return new CodeEmbeddingResult(documents.size(), dimensions);
    }

    Document buildDocument(UUID snapshotId, Map<String, Object> chunk) {
        String content = buildEmbeddingText(chunk);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("snapshot_id", snapshotId.toString());
        metadata.put("service_name", chunk.get("service_name"));
        metadata.put("class_fqn", chunk.get("class_fqn"));
        metadata.put("method_name", chunk.getOrDefault("method_name", ""));
        metadata.put("chunk_type", chunk.get("chunk_type"));
        metadata.put("file_path", chunk.get("file_path"));
        metadata.put("reactive_complexity", chunk.getOrDefault("reactive_complexity", "NONE"));
        metadata.put("annotations", chunk.getOrDefault("annotations", List.of()));
        metadata.put("line_start", chunk.getOrDefault("line_start", 0));
        metadata.put("line_end", chunk.getOrDefault("line_end", 0));
        metadata.put("content_hash", chunk.get("content_hash"));
        return new Document(content, metadata);
    }

    /**
     * Build the text that will be embedded. Prepend class/method context for better semantic retrieval.
     */
    String buildEmbeddingText(Map<String, Object> chunk) {
        StringBuilder sb = new StringBuilder();
        String classFqn = (String) chunk.get("class_fqn");
        String methodName = (String) chunk.getOrDefault("method_name", "");
        String chunkType = (String) chunk.get("chunk_type");
        Object annotationsObj = chunk.getOrDefault("annotations", List.of());

        sb.append("// ").append(chunkType).append(": ").append(classFqn);
        if (methodName != null && !methodName.isEmpty()) {
            sb.append(".").append(methodName);
        }
        sb.append("\n");

        if (annotationsObj instanceof List<?> annots && !annots.isEmpty()) {
            sb.append("// Annotations: ")
                .append(String.join(", ", annots.stream().map(Object::toString).toList()))
                .append("\n");
        }

        Object content = chunk.get("content");
        sb.append(content != null ? content : "");
        return sb.toString();
    }

    /**
     * Fetch all code chunks using search_after pagination.
     */
    List<Map<String, Object>> fetchCodeChunks(UUID snapshotId) throws IOException {
        List<Map<String, Object>> allChunks = new ArrayList<>();
        Object[] searchAfter = null;

        while (true) {
            Map<String, Object> query = new HashMap<>();
            query.put("query", Map.of("term", Map.of("snapshot_id", snapshotId.toString())));
            query.put("size", PAGE_SIZE);
            query.put("sort", List.of(Map.of("_doc", "asc")));
            if (searchAfter != null) {
                query.put("search_after", Arrays.asList(searchAfter));
            }

            OpenSearchClientWrapper.SearchResultWithSort result = openSearch.searchPaginated("code-artifacts", query);
            List<OpenSearchClientWrapper.SearchHitWithSort> hits = result.getHits();
            if (hits.isEmpty()) {
                break;
            }
            for (OpenSearchClientWrapper.SearchHitWithSort hit : hits) {
                allChunks.add(hit.getSourceAsMap());
            }
            OpenSearchClientWrapper.SearchHitWithSort lastHit = hits.get(hits.size() - 1);
            searchAfter = lastHit.getSortValues();
        }
        return allChunks;
    }

    private static <T> List<List<T>> partition(List<T> list, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            batches.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return batches;
    }
}
