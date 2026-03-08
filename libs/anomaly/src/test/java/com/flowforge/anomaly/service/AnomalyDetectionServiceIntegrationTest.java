package com.flowforge.anomaly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.client.OpenSearchClientWrapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@SpringBootTest(classes = AnomalyDetectionServiceIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
@Tag("integration")
class AnomalyDetectionServiceIntegrationTest {

    @MockitoBean OpenSearchClientWrapper openSearch;
    @MockitoBean MinioStorageClient minio;
    @Autowired AnomalyDetectionService service;

    @Test
    void detectAnomalies_fullPipeline_producesEpisodesAndStoresArtifacts() throws Exception {
        var snapshotId = UUID.randomUUID();
        when(openSearch.search(eq("runtime-events"), any(), anyInt()))
            .thenReturn(buildSearchResponse(generateMixedLogEvents("order-service", 500)));

        var result = service.detectAnomalies(snapshotId, List.of("order-service"));

        assertThat(result.totalEpisodes()).isGreaterThanOrEqualTo(0);
        verify(minio, times(1)).putObject(eq("model-artifacts"), contains("order-service.smile"), any(byte[].class), eq("application/octet-stream"));
        verify(minio, times(1)).putJson(eq("evidence"), contains(snapshotId.toString()), any());
    }

    @Test
    void detectAnomalies_multipleServices_trainsPerServiceModels() throws Exception {
        var snapshotId = UUID.randomUUID();
        var services = List.of("order-service", "payment-service", "shipping-service");
        when(openSearch.search(eq("runtime-events"), any(), anyInt()))
            .thenReturn(buildSearchResponse(generateNormalLogEvents("order-service", 200)));

        service.detectAnomalies(snapshotId, services);

        verify(minio, times(3)).putObject(eq("model-artifacts"), contains(".smile"), any(byte[].class), eq("application/octet-stream"));
    }

    private static OpenSearchClientWrapper.SearchResult buildSearchResponse(List<Map<String, Object>> sources) {
        var hits = sources.stream()
            .map(OpenSearchClientWrapper.SearchHit::new)
            .toList();
        return new OpenSearchClientWrapper.SearchResult(hits);
    }

    private static List<Map<String, Object>> generateMixedLogEvents(String serviceName, int count) {
        var list = new java.util.ArrayList<Map<String, Object>>();
        var base = Instant.now().minusSeconds(3600);
        for (int i = 0; i < count; i++) {
            String severity = (i >= 200 && i < 250) ? "ERROR" : "INFO";
            list.add(Map.of(
                "batch_id", UUID.randomUUID().toString(),
                "service_name", serviceName,
                "timestamp", base.plusSeconds(i * 2).toString(),
                "severity", severity,
                "template_id", "tmpl-" + (i % 15),
                "message", "log message " + i,
                "trace_id", i % 3 == 0 ? "trace-" + i : "",
                "span_id", ""
            ));
        }
        return list;
    }

    private static List<Map<String, Object>> generateNormalLogEvents(String serviceName, int count) {
        var list = new java.util.ArrayList<Map<String, Object>>();
        var base = Instant.now().minusSeconds(3600);
        for (int i = 0; i < count; i++) {
            list.add(Map.of(
                "batch_id", UUID.randomUUID().toString(),
                "service_name", serviceName,
                "timestamp", base.plusSeconds(i * 2).toString(),
                "severity", "INFO",
                "template_id", "tmpl-" + (i % 10),
                "message", "log message " + i,
                "trace_id", "",
                "span_id", ""
            ));
        }
        return list;
    }

    @org.springframework.context.annotation.Configuration
    @Import({
        com.flowforge.anomaly.feature.LogFeatureEngineer.class,
        com.flowforge.anomaly.config.AnomalyConfig.class,
        com.flowforge.anomaly.episode.AnomalyEpisodeBuilder.class,
        AnomalyDetectionService.class
    })
    static class TestConfig {
        @Bean
        io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
