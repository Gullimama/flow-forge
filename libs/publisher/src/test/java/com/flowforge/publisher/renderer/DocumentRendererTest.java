package com.flowforge.publisher.renderer;

import com.flowforge.publisher.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentRendererTest {

    private DocumentRenderer renderer;

    @BeforeEach
    void setUp() {
        var config = new freemarker.template.Configuration(
            freemarker.template.Configuration.VERSION_2_3_33);
        config.setObjectWrapper(new freemarker.template.DefaultObjectWrapper(
            freemarker.template.Configuration.VERSION_2_3_33));
        config.setClassLoaderForTemplateLoading(
            getClass().getClassLoader(), "templates");
        config.setDefaultEncoding("UTF-8");
        renderer = new DocumentRenderer(config);
    }

    @Test
    void renderMarkdown_producesValidMarkdownWithTitle() {
        var document = TestFixtures.sampleResearchDocument(2);

        var markdown = renderer.renderMarkdown(document);

        assertThat(markdown).startsWith("# System Flows Research");
        assertThat(markdown).contains("## Table of Contents");
        assertThat(markdown).contains("## Executive Summary");
    }

    @Test
    void renderMarkdown_containsTocLinksForAllFlows() {
        var document = TestFixtures.sampleResearchDocument(3);

        var markdown = renderer.renderMarkdown(document);

        for (var section : document.flowSections()) {
            assertThat(markdown).contains("[%s](#%s)".formatted(
                section.flowName(), section.anchor()));
        }
    }

    @Test
    void renderMarkdown_includesMermaidCodeBlocks() {
        var document = TestFixtures.sampleResearchDocumentWithDiagrams();

        var markdown = renderer.renderMarkdown(document);

        assertThat(markdown).contains("```mermaid");
        assertThat(markdown).contains("sequenceDiagram");
    }

    @Test
    void renderMarkdown_rendersRiskMatrixTable() {
        var document = TestFixtures.sampleResearchDocument(1);

        var markdown = renderer.renderMarkdown(document);

        assertThat(markdown).contains("## Risk Matrix");
        assertThat(markdown).contains("| Flow | Risk | Severity | Category | Mitigation |");
    }

    @Test
    void renderMarkdown_rendersMigrationRoadmapPhases() {
        var document = TestFixtures.sampleResearchDocument(1);

        var markdown = renderer.renderMarkdown(document);

        assertThat(markdown).contains("## Migration Roadmap");
        assertThat(markdown).contains("### Phase 1:");
    }

    @Test
    void renderMarkdown_handlesEmptyFlowSections() {
        var document = TestFixtures.sampleResearchDocument(0);

        var markdown = renderer.renderMarkdown(document);

        assertThat(markdown).contains("## Executive Summary");
        assertThat(markdown).doesNotContain("### Flow:");
    }
}
