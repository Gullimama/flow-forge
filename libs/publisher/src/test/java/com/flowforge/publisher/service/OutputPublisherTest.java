package com.flowforge.publisher.service;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.publisher.assembler.DocumentAssembler;
import com.flowforge.publisher.model.PublishResult;
import com.flowforge.publisher.renderer.DocumentRenderer;
import com.flowforge.publisher.TestFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutputPublisherTest {

    @Mock DocumentAssembler assembler;
    @Mock DocumentRenderer renderer;
    @Mock MinioStorageClient minio;
    @Mock MeterRegistry meterRegistry;

    @InjectMocks OutputPublisher publisher;

    @BeforeEach
    void setUp() {
        when(meterRegistry.counter(anyString())).thenReturn(new SimpleMeterRegistry().counter("test"));
    }

    @Test
    void publish_storesBothMarkdownAndJsonToMinio() {
        var snapshotId = UUID.randomUUID();
        var results = List.of(com.flowforge.synthesis.TestFixtures.sampleFullResult("flow-1"));
        var document = TestFixtures.sampleResearchDocument(1);

        when(assembler.assemble(snapshotId, results)).thenReturn(document);
        when(renderer.renderMarkdown(document)).thenReturn("# Research\n...");

        var publishResult = publisher.publish(snapshotId, results);

        verify(minio).putString(eq("output"),
            contains("system-flows-research.md"),
            anyString(), eq("text/markdown"));
        verify(minio).putJson(eq("output"),
            contains("document.json"), eq(document));
        assertThat(publishResult.flowCount()).isEqualTo(1);
    }

    @Test
    void publish_returnsCorrectMetrics() {
        var snapshotId = UUID.randomUUID();
        var document = TestFixtures.sampleResearchDocument(3);
        when(assembler.assemble(any(), any())).thenReturn(document);
        when(renderer.renderMarkdown(any())).thenReturn("# Doc\ncontent");

        var result = publisher.publish(snapshotId, List.of());

        assertThat(result.flowCount()).isEqualTo(3);
        assertThat(result.markdownLength()).isGreaterThan(0);
    }
}
