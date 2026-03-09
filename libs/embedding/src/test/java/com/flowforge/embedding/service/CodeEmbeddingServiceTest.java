package com.flowforge.embedding.service;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.client.OpenSearchClientWrapper;
import com.flowforge.vectorstore.service.VectorStoreService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeEmbeddingServiceTest {

    @Mock
    VectorStoreService vectorStoreService;
    @Mock
    OpenSearchClientWrapper openSearch;
    @Mock
    MinioStorageClient minio;

    MeterRegistry meterRegistry;
    CodeEmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        embeddingService = new CodeEmbeddingService(vectorStoreService, openSearch, minio, meterRegistry);
    }

    @Test
    void buildEmbeddingText_includesContextPrefix() {
        Map<String, Object> chunk = Map.of(
            "class_fqn", "com.example.BookingController",
            "method_name", "getBooking",
            "chunk_type", "METHOD",
            "annotations", List.of("@GetMapping", "@ResponseBody"),
            "content", "public Mono<Booking> getBooking(String id) { return repo.findById(id); }"
        );

        String text = embeddingService.buildEmbeddingText(chunk);

        assertThat(text).startsWith("// METHOD: com.example.BookingController.getBooking");
        assertThat(text).contains("// Annotations: @GetMapping, @ResponseBody");
        assertThat(text).contains("public Mono<Booking> getBooking");
    }

    @Test
    void buildEmbeddingText_classChunk_omitsMethodName() {
        Map<String, Object> chunk = Map.of(
            "class_fqn", "com.example.BookingService",
            "method_name", "",
            "chunk_type", "CLASS",
            "annotations", List.of(),
            "content", "public class BookingService { }"
        );

        String text = embeddingService.buildEmbeddingText(chunk);

        assertThat(text).startsWith("// CLASS: com.example.BookingService\n");
        assertThat(text).doesNotContain("Annotations:");
    }

    @Test
    void buildDocument_setsAllMetadataFields() {
        UUID snapshotId = UUID.randomUUID();
        Map<String, Object> chunk = new java.util.HashMap<>(Map.of(
            "class_fqn", "com.example.Foo",
            "method_name", "bar",
            "chunk_type", "METHOD",
            "service_name", "foo-service",
            "file_path", "Foo.java",
            "content_hash", "abc123",
            "reactive_complexity", "SIMPLE",
            "annotations", List.of("@Override"),
            "content", "void bar() {}"
        ));
        chunk.put("line_start", 10);
        chunk.put("line_end", 25);

        var doc = embeddingService.buildDocument(snapshotId, chunk);

        assertThat(doc.getMetadata()).containsEntry("snapshot_id", snapshotId.toString());
        assertThat(doc.getMetadata()).containsEntry("service_name", "foo-service");
        assertThat(doc.getMetadata()).containsEntry("class_fqn", "com.example.Foo");
        assertThat(doc.getMetadata()).containsEntry("reactive_complexity", "SIMPLE");
    }

    @Test
    void embedSnapshot_batchesDocumentsIn64ChunkBatches() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        List<Map<String, Object>> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) {
            chunks.add(codeChunkMap("com.ex.Class" + i, "method" + i));
        }
        when(openSearch.searchPaginated(eq("code-artifacts"), any()))
            .thenReturn(searchResponseWith(chunks), emptySearchResponse());

        embeddingService.embedSnapshot(snapshotId);

        verify(vectorStoreService, times(3)).addCodeDocuments(anyList());
    }

    @Test
    void embedSnapshot_storesEvidenceInMinio() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        when(openSearch.searchPaginated(eq("code-artifacts"), any()))
            .thenReturn(searchResponseWith(List.of(codeChunkMap("com.ex.C", "m"))), emptySearchResponse());

        embeddingService.embedSnapshot(snapshotId);

        verify(minio).putJson(eq("evidence"), ArgumentMatchers.contains("embeddings/code/" + snapshotId), any());
    }

    @Test
    void embedSnapshot_emptySnapshot_returnsZeroChunks() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        when(openSearch.searchPaginated(eq("code-artifacts"), any()))
            .thenReturn(emptySearchResponse());

        var result = embeddingService.embedSnapshot(snapshotId);

        assertThat(result.chunksEmbedded()).isZero();
        verify(vectorStoreService, never()).addCodeDocuments(anyList());
    }

    @Test
    void fetchCodeChunks_usesSearchAfterPagination() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        List<Map<String, Object>> page1 = new java.util.ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            page1.add(codeChunkMap("c" + i, "m"));
        }
        List<Map<String, Object>> page2 = new java.util.ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            page2.add(codeChunkMap("c2-" + i, "m"));
        }
        when(openSearch.searchPaginated(eq("code-artifacts"), any()))
            .thenReturn(searchResponseWith(page1, new Object[]{12345L}),
                searchResponseWith(page2, new Object[]{67890L}),
                emptySearchResponse());

        embeddingService.embedSnapshot(snapshotId);

        verify(openSearch, times(3)).searchPaginated(eq("code-artifacts"), any());
    }

    private static Map<String, Object> codeChunkMap(String classFqn, String methodName) {
        return Map.of(
            "class_fqn", classFqn,
            "method_name", methodName,
            "chunk_type", "METHOD",
            "service_name", "svc",
            "file_path", "Foo.java",
            "content_hash", "h1",
            "content", "void m() {}"
        );
    }

    private static OpenSearchClientWrapper.SearchResultWithSort searchResponseWith(List<Map<String, Object>> chunks) {
        return searchResponseWith(chunks, new Object[]{0L});
    }

    private static OpenSearchClientWrapper.SearchResultWithSort searchResponseWith(
            List<Map<String, Object>> chunks, Object[] lastSortValues) {
        List<OpenSearchClientWrapper.SearchHitWithSort> hits = new java.util.ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Object[] sortV = (i == chunks.size() - 1) ? lastSortValues : new Object[]{i};
            hits.add(new OpenSearchClientWrapper.SearchHitWithSort(chunks.get(i), sortV));
        }
        return new OpenSearchClientWrapper.SearchResultWithSort(hits);
    }

    private static OpenSearchClientWrapper.SearchResultWithSort emptySearchResponse() {
        return new OpenSearchClientWrapper.SearchResultWithSort(List.of());
    }
}
