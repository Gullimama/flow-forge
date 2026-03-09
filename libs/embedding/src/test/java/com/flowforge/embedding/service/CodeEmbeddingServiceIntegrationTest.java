package com.flowforge.embedding.service;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.client.OpenSearchClientWrapper;
import com.flowforge.vectorstore.service.VectorStoreService;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import com.flowforge.common.config.FlowForgeProperties;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest(classes = CodeEmbeddingServiceIntegrationTest.TestConfig.class)
@SpringJUnitConfig
@Tag("integration")
@Disabled("NoSuchMethodError when loading OpenAiApi/EmbeddingModel with vector-store on classpath; run in Boot app with single Spring AI version")
class CodeEmbeddingServiceIntegrationTest {

    static final WireMockServer wireMock = new WireMockServer(wireMockConfig().port(18081));

    @DynamicPropertySource
    static void setTeiUrl(DynamicPropertyRegistry registry) {
        registry.add("flowforge.tei.code-url", () -> "http://localhost:" + wireMock.port());
    }

    @BeforeAll
    static void startWireMock() {
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @Autowired
    CodeEmbeddingService embeddingService;
    @MockitoBean
    OpenSearchClientWrapper openSearch;
    @MockitoBean
    MinioStorageClient minio;
    @MockitoBean
    VectorStoreService vectorStoreService;

    @BeforeEach
    void setupWireMock() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo("/v1/embeddings"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(embeddingResponse(1024))));

        wireMock.stubFor(get(urlEqualTo("/health"))
            .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"ok\"}")));
    }

    @Test
    void embedSnapshot_sendsChunksToTeiAndStoresInQdrant() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        List<Map<String, Object>> chunks = List.of(
            codeChunkMap("com.example.BookingController", "getBooking"),
            codeChunkMap("com.example.PaymentService", "processPayment")
        );
        doReturn(searchResponseWith(chunks), emptySearchResponse())
            .when(openSearch).searchPaginated(eq("code-artifacts"), any());

        var result = embeddingService.embedSnapshot(snapshotId);

        assertThat(result.chunksEmbedded()).isEqualTo(2);
        assertThat(result.dimensions()).isEqualTo(1024);
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/embeddings"))
            .withHeader("Content-Type", containing("application/json")));
    }

    @Test
    void embedSnapshot_contextPrefixIncludedInEmbeddingRequest() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        List<Map<String, Object>> chunks = List.of(codeChunkMap("com.example.OrderService", "placeOrder"));
        doReturn(searchResponseWith(chunks), emptySearchResponse())
            .when(openSearch).searchPaginated(eq("code-artifacts"), any());

        embeddingService.embedSnapshot(snapshotId);

        wireMock.verify(postRequestedFor(urlEqualTo("/v1/embeddings"))
            .withRequestBody(containing("com.example.OrderService.placeOrder")));
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
        var hits = chunks.stream()
            .map(c -> new OpenSearchClientWrapper.SearchHitWithSort(c, new Object[]{0L}))
            .toList();
        return new OpenSearchClientWrapper.SearchResultWithSort(hits);
    }

    private static OpenSearchClientWrapper.SearchResultWithSort emptySearchResponse() {
        return new OpenSearchClientWrapper.SearchResultWithSort(List.of());
    }

    private static String embeddingResponse(int dimensions) {
        String embedding = IntStream.range(0, dimensions)
            .mapToObj(i -> String.valueOf(Math.random()))
            .collect(Collectors.joining(","));
        return """
            {
              "data": [{"embedding": [%s], "index": 0}],
              "model": "codesage/codesage-large",
              "usage": {"prompt_tokens": 50, "total_tokens": 50}
            }
            """.formatted(embedding);
    }

    @Configuration
    @EnableConfigurationProperties(FlowForgeProperties.class)
    @ComponentScan("com.flowforge.embedding")
    @Import(com.flowforge.embedding.config.EmbeddingConfig.class)
    static class TestConfig {
        @Bean
        io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }
    }
}
