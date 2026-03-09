package com.flowforge.publisher.renderer;

import com.flowforge.publisher.model.ResearchDocument;
import java.io.StringWriter;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Renders a ResearchDocument to Markdown using FreeMarker.
 */
@Service
public class DocumentRenderer {

    private final freemarker.template.Configuration freeMarkerConfig;

    public DocumentRenderer(freemarker.template.Configuration freeMarkerConfig) {
        this.freeMarkerConfig = freeMarkerConfig;
    }

    /**
     * Render a ResearchDocument to Markdown string.
     */
    public String renderMarkdown(ResearchDocument document) {
        try {
            var template = freeMarkerConfig.getTemplate("research-document.ftl");
            var generatedAtFormatted = document.generatedAt()
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            var model = Map.<String, Object>of(
                "document", document,
                "generatedAtFormatted", generatedAtFormatted
            );

            var writer = new StringWriter();
            template.process(model, writer);
            return writer.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to render document", e);
        }
    }
}
