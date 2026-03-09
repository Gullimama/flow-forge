package com.flowforge.reranker.client;

import com.flowforge.reranker.RerankerTestFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CrossEncoderRerankerTest {

    private CrossEncoderReranker reranker;
    private MockRestServiceServer mockServer;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl("http://localhost:8083").build();
        reranker = new CrossEncoderReranker(restClient, meterRegistry);
    }

    @Test
    void rerank_returnsSortedByScoreDescending() {
        mockServer.expect(requestTo("http://localhost:8083/rerank"))
            .andRespond(withSuccess("""
                [{"index":0,"score":0.3,"text":"doc0"},
                 {"index":1,"score":0.9,"text":"doc1"},
                 {"index":2,"score":0.6,"text":"doc2"}]
                """.replace("\n", ""), MediaType.APPLICATION_JSON));

        var results = reranker.rerank("query", List.of("doc0", "doc1", "doc2"), 3);

        assertThat(results).extracting(CrossEncoderReranker.RerankResult::score)
            .containsExactly(0.9, 0.6, 0.3);
        assertThat(results.get(0).index()).isEqualTo(1);
        mockServer.verify();
    }

    @Test
    void rerank_respectsTopKLimit() {
        mockServer.expect(requestTo("http://localhost:8083/rerank"))
            .andRespond(withSuccess(RerankerTestFixtures.rerankResponse(10), MediaType.APPLICATION_JSON));

        var results = reranker.rerank("query", RerankerTestFixtures.texts(10), 3);
        assertThat(results).hasSize(3);
        mockServer.verify();
    }

    @Test
    void rerank_emptyInput_returnsEmptyList() {
        var results = reranker.rerank("query", List.of(), 5);
        assertThat(results).isEmpty();
        mockServer.verify();
    }

    @Test
    void rerankDocuments_preservesMetadataAndAddsRerankerScore() {
        mockServer.expect(requestTo("http://localhost:8083/rerank"))
            .andRespond(withSuccess("""
                [{"index":0,"score":0.85,"text":"content A"},
                 {"index":1,"score":0.45,"text":"content B"}]
                """.replace("\n", ""), MediaType.APPLICATION_JSON));

        var docs = List.of(
            new Document("content A", java.util.Map.of("source", "vector")),
            new Document("content B", java.util.Map.of("source", "bm25"))
        );
        var results = reranker.rerankDocuments("query", docs, 2);

        assertThat(results.get(0).getMetadata()).containsEntry("reranker_score", 0.85);
        assertThat(results.get(0).getMetadata()).containsEntry("source", "vector");
        mockServer.verify();
    }

    @Test
    void rerank_teiReturnsNull_returnsEmptyList() {
        mockServer.expect(requestTo("http://localhost:8083/rerank"))
            .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        var results = reranker.rerank("query", List.of("a", "b"), 2);
        assertThat(results).isEmpty();
        mockServer.verify();
    }
}
