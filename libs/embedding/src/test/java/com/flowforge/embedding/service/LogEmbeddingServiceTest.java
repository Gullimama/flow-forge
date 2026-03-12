package com.flowforge.embedding.service;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.client.OpenSearchClientWrapper;
import com.flowforge.logparser.model.ParsedLogEvent;
import com.flowforge.vectorstore.service.VectorStoreService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogEmbeddingServiceTest {

    @Mock
    VectorStoreService vectorStoreService;
    @Mock
    LogEmbeddingTextBuilder textBuilder;
    @Mock
    OpenSearchClientWrapper openSearch;
    @Mock
    MinioStorageClient minio;
    @Mock
    EmbeddingModel logEmbeddingModel;

    MeterRegistry meterRegistry;
    LogEmbeddingService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        lenient().when(logEmbeddingModel.dimensions()).thenReturn(1024);
        service = new LogEmbeddingService(vectorStoreService, textBuilder, openSearch, minio, meterRegistry, logEmbeddingModel);
    }

    @Test
    void stratifiedSampling_keepsAllErrorEvents() {
        var errors = logEvents(200, ParsedLogEvent.LogSeverity.ERROR);
        var infos = logEvents(60_000, ParsedLogEvent.LogSeverity.INFO);
        var all = new ArrayList<>(errors);
        all.addAll(infos);

        var sampled = service.fetchAndSampleEvents(all, 50_000);

        long errorCount = sampled.stream()
            .filter(e -> e.severity() == ParsedLogEvent.LogSeverity.ERROR).count();
        assertThat(errorCount).isEqualTo(200);
        assertThat(sampled).hasSizeLessThanOrEqualTo(50_000);
    }

    @Test
    void stratifiedSampling_samplesAcrossServices() {
        var svcA = logEvents("svc-a", 30_000, ParsedLogEvent.LogSeverity.INFO);
        var svcB = logEvents("svc-b", 30_000, ParsedLogEvent.LogSeverity.INFO);
        var all = new ArrayList<>(svcA);
        all.addAll(svcB);

        var sampled = service.fetchAndSampleEvents(all, 10_000);

        var bySvc = sampled.stream().collect(Collectors.groupingBy(ParsedLogEvent::serviceName));
        assertThat(bySvc.get("svc-a")).hasSizeGreaterThan(0);
        assertThat(bySvc.get("svc-b")).hasSizeGreaterThan(0);
    }

    @Test
    void embedSnapshot_storesStatsInMinio() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        doReturn(List.of(Map.of("clusterId", "c1", "template", "t1", "matchCount", 10)))
            .when(minio).getJson(eq("evidence"), eq("drain-clusters/" + snapshotId + ".json"), (TypeReference<?>) any());
        when(openSearch.searchPaginated(eq("runtime-events"), any()))
            .thenReturn(emptySearchResult());
        when(textBuilder.buildTemplateText(any(String.class), any(LogEmbeddingTextBuilder.StoredDrainCluster.class)))
            .thenReturn("passage: [svc] template t1");

        service.embedSnapshot(snapshotId);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(minio).putJson(eq("evidence"), keyCaptor.capture(), any());
        assertThat(keyCaptor.getValue()).contains("embeddings/log/");
    }

    @Test
    void embedSnapshot_batchesByConfiguredSize() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        doReturn(List.of(
                Map.of("clusterId", "c1", "template", "t1", "matchCount", 1),
                Map.of("clusterId", "c2", "template", "t2", "matchCount", 2)
            ))
            .when(minio).getJson(eq("evidence"), eq("drain-clusters/" + snapshotId + ".json"), (TypeReference<?>) any());
        when(textBuilder.buildTemplateText(any(String.class), any(LogEmbeddingTextBuilder.StoredDrainCluster.class)))
            .thenReturn("passage: template");
        when(textBuilder.buildPassageText(any(ParsedLogEvent.class))).thenReturn("passage: event");
        var hits = new ArrayList<OpenSearchClientWrapper.SearchHitWithSort>();
        for (int i = 0; i < 300; i++) {
            hits.add(new OpenSearchClientWrapper.SearchHitWithSort(
                Map.of("batch_id", snapshotId.toString(), "service_name", "svc", "timestamp", java.time.Instant.now().toString(),
                    "severity", "INFO", "template_id", "c1", "message", "msg"),
                new Object[]{i}));
        }
        when(openSearch.searchPaginated(eq("runtime-events"), any()))
            .thenReturn(new OpenSearchClientWrapper.SearchResultWithSort(hits), emptySearchResult());

        service.embedSnapshot(snapshotId);

        verify(vectorStoreService, times(3)).addLogDocuments(any(List.class));
    }

    private static List<ParsedLogEvent> logEvents(int count, ParsedLogEvent.LogSeverity severity) {
        return logEvents("svc", count, severity);
    }

    private static List<ParsedLogEvent> logEvents(String service, int count, ParsedLogEvent.LogSeverity severity) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(i -> LogEmbeddingTestFixtures.parsedLogEvent(service, severity, "msg"))
            .toList();
    }

    private static OpenSearchClientWrapper.SearchResultWithSort emptySearchResult() {
        return new OpenSearchClientWrapper.SearchResultWithSort(List.of());
    }
}
