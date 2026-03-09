package com.flowforge.publisher.service;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.publisher.assembler.DocumentAssembler;
import com.flowforge.publisher.model.PublishResult;
import com.flowforge.publisher.renderer.DocumentRenderer;
import com.flowforge.synthesis.model.SynthesisFullResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Assembles, renders, and publishes the final research document to MinIO.
 */
@Service
public class OutputPublisher {

    private final DocumentAssembler assembler;
    private final DocumentRenderer renderer;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    public OutputPublisher(DocumentAssembler assembler,
                           DocumentRenderer renderer,
                           MinioStorageClient minio,
                           MeterRegistry meterRegistry) {
        this.assembler = assembler;
        this.renderer = renderer;
        this.minio = minio;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Assemble and publish the final research document.
     */
    public PublishResult publish(UUID snapshotId, List<SynthesisFullResult> results) {
        var document = assembler.assemble(snapshotId, results);
        var markdown = renderer.renderMarkdown(document);

        var outputKey = "system-flows-research/%s/system-flows-research.md".formatted(snapshotId);
        minio.putString("output", outputKey, markdown, "text/markdown");

        var jsonKey = "system-flows-research/%s/document.json".formatted(snapshotId);
        minio.putJson("output", jsonKey, document);

        meterRegistry.counter("flowforge.output.documents.published").increment();

        return new PublishResult(
            outputKey,
            markdown.length(),
            document.flowSections().size(),
            document.riskMatrix().entries().size()
        );
    }
}
