# Stage 23 — Output Publisher (FreeMarker Templates)

## Goal

Render the final `system-flows-research.md` document from all synthesis results using **FreeMarker** template engine. Combine executive summary, per-flow sections, Mermaid diagrams, risk matrices, and migration roadmap into a polished Markdown document.

> **Why FreeMarker?** FreeMarker is a mature Java-native template engine with rich control flow, macro support, and excellent Markdown compatibility. It replaces Jinja2 from the Python stack, with nearly identical syntax concepts (loops, conditionals, includes).

## Prerequisites

- Stage 22 (complete synthesis results — all 6 stages)

## What to build

### 23.1 FreeMarker configuration

```java
@Configuration
public class FreeMarkerConfig {

    @Bean
    public freemarker.template.Configuration freeMarkerConfiguration() {
        var config = new freemarker.template.Configuration(
            freemarker.template.Configuration.VERSION_2_3_33);
        config.setClassLoaderForTemplateLoading(
            getClass().getClassLoader(), "templates");
        config.setDefaultEncoding("UTF-8");
        config.setOutputEncoding("UTF-8");
        config.setLogTemplateExceptions(false);
        config.setWrapUncheckedExceptions(true);
        return config;
    }
}
```

### 23.2 Document model

```java
public record ResearchDocument(
    String title,
    Instant generatedAt,
    UUID snapshotId,
    ExecutiveSummary executiveSummary,
    List<FlowSection> flowSections,
    RiskMatrix riskMatrix,
    MigrationRoadmap roadmap,
    List<AppendixItem> appendices,
    DocumentMetadata metadata
) {}

public record ExecutiveSummary(
    String overview,
    int totalFlows,
    int totalServices,
    int criticalRisks,
    int highRisks,
    List<String> topFindings,
    String recommendedApproach
) {}

public record FlowSection(
    String flowName,
    String anchor,              // Markdown anchor for TOC
    FlowAnalysisOutput analysis,
    CodeExplanationOutput codeExplanation,
    RiskAssessmentOutput riskAssessment,
    DependencyMappingOutput dependencyMapping,
    MigrationPlanOutput migrationPlan,
    FinalNarrativeOutput narrative
) {}

public record RiskMatrix(
    List<RiskMatrixEntry> entries,
    Map<String, Long> bySeverity,
    Map<String, Long> byCategory
) {}

public record RiskMatrixEntry(
    String flowName,
    String riskDescription,
    String severity,
    String category,
    String mitigation
) {}

public record MigrationRoadmap(
    List<RoadmapPhase> phases,
    String totalEstimatedDuration,
    String recommendedTeamSize
) {}

public record RoadmapPhase(
    int order,
    String name,
    String duration,
    List<String> flows,
    List<String> deliverables
) {}

public record AppendixItem(String title, String content) {}
public record DocumentMetadata(String model, int totalTokensUsed, long totalLatencyMs) {}
```

### 23.3 Document assembler

```java
@Service
public class DocumentAssembler {

    /**
     * Assemble a ResearchDocument from synthesis results.
     */
    public ResearchDocument assemble(UUID snapshotId, List<SynthesisFullResult> results) {
        var flowSections = results.stream()
            .map(this::buildFlowSection)
            .sorted(Comparator.comparing(FlowSection::flowName))
            .toList();

        var riskMatrix = buildRiskMatrix(results);
        var roadmap = buildRoadmap(results);
        var summary = buildExecutiveSummary(flowSections, riskMatrix);

        return new ResearchDocument(
            "System Flows Research — Migration Analysis",
            Instant.now(),
            snapshotId,
            summary,
            flowSections,
            riskMatrix,
            roadmap,
            buildAppendices(results),
            new DocumentMetadata("Qwen2.5-Coder-32B-Instruct", 0, 0)
        );
    }

    private FlowSection buildFlowSection(SynthesisFullResult result) {
        return new FlowSection(
            result.narrative().flowName(),
            slugify(result.narrative().flowName()),
            result.stages1to3().flowAnalysis(),
            result.stages1to3().codeExplanation(),
            result.stages1to3().riskAssessment(),
            result.dependencyMapping(),
            result.migrationPlan(),
            result.narrative()
        );
    }

    private RiskMatrix buildRiskMatrix(List<SynthesisFullResult> results) {
        var entries = results.stream()
            .flatMap(r -> r.stages1to3().riskAssessment().risks().stream()
                .map(risk -> new RiskMatrixEntry(
                    r.narrative().flowName(),
                    risk.description(),
                    risk.severity().name(),
                    risk.category(),
                    risk.mitigation()
                )))
            .toList();

        return new RiskMatrix(entries,
            entries.stream().collect(Collectors.groupingBy(RiskMatrixEntry::severity, Collectors.counting())),
            entries.stream().collect(Collectors.groupingBy(RiskMatrixEntry::category, Collectors.counting()))
        );
    }

    private String slugify(String text) {
        return text.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    }
}
```

### 23.4 FreeMarker templates

**Main template: `templates/research-document.ftl`**
```freemarker
# ${document.title}

> Generated: ${document.generatedAt?string["yyyy-MM-dd HH:mm:ss"]} UTC
> Snapshot: ${document.snapshotId}

## Table of Contents

1. [Executive Summary](#executive-summary)
<#list document.flowSections as flow>
${flow?counter + 1}. [${flow.flowName}](#${flow.anchor})
</#list>
${document.flowSections?size + 2}. [Risk Matrix](#risk-matrix)
${document.flowSections?size + 3}. [Migration Roadmap](#migration-roadmap)
${document.flowSections?size + 4}. [Appendices](#appendices)

---

## Executive Summary

${document.executiveSummary.overview}

| Metric | Value |
|---|---|
| Total Flows Analyzed | ${document.executiveSummary.totalFlows} |
| Services Involved | ${document.executiveSummary.totalServices} |
| Critical Risks | ${document.executiveSummary.criticalRisks} |
| High Risks | ${document.executiveSummary.highRisks} |

### Top Findings

<#list document.executiveSummary.topFindings as finding>
- ${finding}
</#list>

### Recommended Approach

${document.executiveSummary.recommendedApproach}

---

<#list document.flowSections as flow>
<#include "flow-section.ftl">

---

</#list>

## Risk Matrix

| Flow | Risk | Severity | Category | Mitigation |
|---|---|---|---|---|
<#list document.riskMatrix.entries as entry>
| ${entry.flowName} | ${entry.riskDescription} | ${entry.severity} | ${entry.category} | ${entry.mitigation} |
</#list>

## Migration Roadmap

<#list document.roadmap.phases as phase>
### Phase ${phase.order}: ${phase.name} (${phase.duration})

**Flows:** ${phase.flows?join(", ")}

**Deliverables:**
<#list phase.deliverables as deliverable>
- ${deliverable}
</#list>

</#list>

**Estimated Total Duration:** ${document.roadmap.totalEstimatedDuration}
**Recommended Team Size:** ${document.roadmap.recommendedTeamSize}

## Appendices

<#list document.appendices as appendix>
### ${appendix.title}

${appendix.content}

</#list>
```

### 23.5 Document renderer

```java
@Service
public class DocumentRenderer {

    private final freemarker.template.Configuration freeMarkerConfig;

    /**
     * Render a ResearchDocument to Markdown string.
     */
    public String renderMarkdown(ResearchDocument document) {
        try {
            var template = freeMarkerConfig.getTemplate("research-document.ftl");
            var model = Map.of("document", document);

            var writer = new StringWriter();
            template.process(model, writer);
            return writer.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to render document", e);
        }
    }
}
```

### 23.6 Output publisher service

```java
@Service
public class OutputPublisher {

    private final DocumentAssembler assembler;
    private final DocumentRenderer renderer;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    /**
     * Assemble and publish the final research document.
     */
    public PublishResult publish(UUID snapshotId, List<SynthesisFullResult> results) {
        // 1. Assemble document model
        var document = assembler.assemble(snapshotId, results);

        // 2. Render to Markdown
        var markdown = renderer.renderMarkdown(document);

        // 3. Store in MinIO output bucket
        var outputKey = "system-flows-research/%s/system-flows-research.md".formatted(snapshotId);
        minio.putString("output", outputKey, markdown, "text/markdown");

        // 4. Also store the structured document as JSON
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

public record PublishResult(String outputKey, int markdownLength, int flowCount, int riskCount) {}
```

### 23.7 Dependencies

```kotlin
// libs/publisher/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:synthesis"))
    implementation(libs.freemarker)     // org.freemarker:freemarker:2.3.33
}
```

Add to version catalog:
```toml
[versions]
freemarker = "2.3.33"

[libraries]
freemarker = { module = "org.freemarker:freemarker", version.ref = "freemarker" }
```

### AKS Deployment Context

This module is compiled into the FlowForge pipeline runner image (`flowforgeacr.azurecr.io/flowforge-pipeline:latest`) and executes as an Argo Workflow DAG task in the `flowforge` namespace (see Stage 28). It does not require its own Kubernetes Deployment.

**In-cluster service DNS names used:**

| Service | DNS | Port |
|---|---|---|
| PostgreSQL | `flowforge-pg-postgresql.flowforge-infra.svc.cluster.local` | 5432 |
| MinIO | `flowforge-minio.flowforge-infra.svc.cluster.local` | 9000 |

**Argo task resource class:** CPU (`cpupool` node selector)

---

## Testing & Verification Strategy

### Unit Tests

**`DocumentAssemblerTest`** — validates document assembly, slugification, risk matrix building, and executive summary generation.

```java
@ExtendWith(MockitoExtension.class)
class DocumentAssemblerTest {

    DocumentAssembler assembler = new DocumentAssembler();

    @Test
    void assemble_createsDocumentWithCorrectFlowSectionCount() {
        var results = List.of(
            TestFixtures.sampleFullResult("order-flow"),
            TestFixtures.sampleFullResult("payment-flow"),
            TestFixtures.sampleFullResult("inventory-flow"));

        var document = assembler.assemble(UUID.randomUUID(), results);

        assertThat(document.flowSections()).hasSize(3);
        assertThat(document.flowSections())
            .extracting(FlowSection::flowName)
            .isSorted();
    }

    @Test
    void assemble_generatesCorrectSlugAnchors() {
        var results = List.of(
            TestFixtures.sampleFullResult("Order Processing Flow"));

        var document = assembler.assemble(UUID.randomUUID(), results);

        assertThat(document.flowSections().get(0).anchor())
            .isEqualTo("order-processing-flow");
    }

    @Test
    void slugify_handlesSpecialCharacters() {
        assertThat(assembler.slugify("My Flow — v2.0 (beta)"))
            .isEqualTo("my-flow-v2-0-beta");
        assertThat(assembler.slugify("---leading-trailing---"))
            .isEqualTo("leading-trailing");
        assertThat(assembler.slugify("UPPERCASE")).isEqualTo("uppercase");
    }

    @Test
    void buildRiskMatrix_aggregatesAllRisksAcrossFlows() {
        var results = List.of(
            TestFixtures.fullResultWithRisks(2),
            TestFixtures.fullResultWithRisks(3));

        var document = assembler.assemble(UUID.randomUUID(), results);

        assertThat(document.riskMatrix().entries()).hasSize(5);
    }

    @Test
    void buildRiskMatrix_groupsBySeverityAndCategory() {
        var results = List.of(TestFixtures.fullResultWithMixedRisks());

        var document = assembler.assemble(UUID.randomUUID(), results);

        assertThat(document.riskMatrix().bySeverity()).containsKeys("HIGH", "MEDIUM");
        assertThat(document.riskMatrix().byCategory()).containsKeys("REACTIVE", "COUPLING");
    }

    @Test
    void buildExecutiveSummary_computesCorrectCounts() {
        var results = List.of(
            TestFixtures.sampleFullResult("flow-1"),
            TestFixtures.sampleFullResult("flow-2"));

        var document = assembler.assemble(UUID.randomUUID(), results);

        assertThat(document.executiveSummary().totalFlows()).isEqualTo(2);
        assertThat(document.executiveSummary().topFindings()).isNotEmpty();
    }

    @Test
    void buildRoadmap_createsOrderedPhases() {
        var results = List.of(TestFixtures.sampleFullResult("flow-1"));

        var document = assembler.assemble(UUID.randomUUID(), results);

        assertThat(document.roadmap().phases())
            .extracting(RoadmapPhase::order)
            .isSorted();
    }
}
```

**`DocumentRendererTest`** — validates FreeMarker template rendering.

```java
class DocumentRendererTest {

    private DocumentRenderer renderer;

    @BeforeEach
    void setUp() {
        var config = new freemarker.template.Configuration(
            freemarker.template.Configuration.VERSION_2_3_33);
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
```

**`OutputPublisherTest`** — validates assembly, rendering, and MinIO storage orchestration.

```java
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
        var results = List.of(TestFixtures.sampleFullResult("flow-1"));
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
```

### Integration Tests

**`OutputPublisherIntegrationTest`** — end-to-end rendering and MinIO storage with Testcontainers.

```java
@SpringBootTest
@Testcontainers
@Tag("integration")
class OutputPublisherIntegrationTest {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:RELEASE.2024-02-17T01-15-57Z");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("flowforge.minio.endpoint", minio::getS3URL);
    }

    @Autowired OutputPublisher publisher;
    @Autowired MinioStorageClient minioClient;

    @Test
    void publish_rendersAndStoresFullDocument() {
        var snapshotId = UUID.randomUUID();
        var results = List.of(
            TestFixtures.sampleFullResult("order-flow"),
            TestFixtures.sampleFullResult("payment-flow"));

        var publishResult = publisher.publish(snapshotId, results);

        var mdKey = "system-flows-research/%s/system-flows-research.md"
            .formatted(snapshotId);
        var markdown = minioClient.getString("output", mdKey);
        assertThat(markdown).contains("# System Flows Research");
        assertThat(markdown).contains("order-flow");
        assertThat(markdown).contains("payment-flow");

        var jsonKey = "system-flows-research/%s/document.json"
            .formatted(snapshotId);
        assertThat(minioClient.exists("output", jsonKey)).isTrue();
    }

    @Test
    void publish_largeDocument_rendersWithin5Seconds() {
        var snapshotId = UUID.randomUUID();
        var results = IntStream.range(0, 15)
            .mapToObj(i -> TestFixtures.sampleFullResult("flow-" + i))
            .toList();

        var start = System.currentTimeMillis();
        publisher.publish(snapshotId, results);
        var elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(5000);
    }
}
```

**`FreeMarkerTemplateIntegrationTest`** — validates FreeMarker template loading and basic rendering from classpath.

```java
@SpringBootTest
@Tag("integration")
class FreeMarkerTemplateIntegrationTest {

    @Autowired freemarker.template.Configuration freeMarkerConfig;

    @Test
    void templatesLoadFromClasspath() {
        assertThatCode(() -> freeMarkerConfig.getTemplate("research-document.ftl"))
            .doesNotThrowAnyException();
        assertThatCode(() -> freeMarkerConfig.getTemplate("flow-section.ftl"))
            .doesNotThrowAnyException();
    }

    @Test
    void templateRenders_withMissingOptionalField_usesDefault() {
        var document = TestFixtures.documentWithNullAppendices();
        var template = freeMarkerConfig.getTemplate("research-document.ftl");
        var writer = new StringWriter();

        assertThatCode(() -> template.process(Map.of("document", document), writer))
            .doesNotThrowAnyException();
    }
}
```

### Test Fixtures & Sample Data

Create test fixtures at `libs/publisher/src/test/java/com/flowforge/publisher/TestFixtures.java`:

- **`sampleFullResult(String flowName)`** — a `SynthesisFullResult` with all 6 stage outputs populated, parameterized by flow name
- **`fullResultWithRisks(int riskCount)`** — variant with a configurable number of `MigrationRisk` entries for risk matrix testing
- **`fullResultWithMixedRisks()`** — variant with risks spanning multiple severities (HIGH, MEDIUM) and categories (REACTIVE, COUPLING)
- **`sampleResearchDocument(int flowCount)`** — pre-assembled `ResearchDocument` with the given number of flow sections, a populated risk matrix, and roadmap
- **`sampleResearchDocumentWithDiagrams()`** — variant where flow sections include `DiagramSpec` entries with Mermaid `sequenceDiagram` code
- **`documentWithNullAppendices()`** — edge case document with empty or null optional fields for error-handling tests

FreeMarker templates for testing under `libs/publisher/src/test/resources/templates/`:

- Copy production templates to the test classpath to ensure template integration tests use the same templates as production
- **`test-flow-section.ftl`** — minimal flow section template for isolated rendering tests

### Mocking Strategy

| Dependency | Mock or Real | Rationale |
|---|---|---|
| `DocumentAssembler` | **Real** (assembler tests) / **Mock** (publisher tests) | Assembler logic is pure computation — test it directly; mock in publisher to isolate rendering |
| `DocumentRenderer` | **Real** (renderer tests) / **Mock** (publisher tests) | Renderer needs real FreeMarker config; mock in publisher to isolate storage |
| `FreeMarker Configuration` | **Real** | Loads from test classpath; validates template syntax and variable binding |
| `MinioStorageClient` | **Mock** (unit) / **Testcontainers** (integration) | Unit tests verify method calls; integration tests verify actual storage |
| `MeterRegistry` | **SimpleMeterRegistry** | Lightweight in-memory registry |

### CI/CD Considerations

- Tag unit tests with `@Tag("unit")`, integration tests with `@Tag("integration")`
- FreeMarker template files must be on the test classpath — configure Gradle `processTestResources` to include `src/main/resources/templates`
- Integration tests require Docker for MinIO Testcontainers
- Add a `templateValidation` CI task that loads all `.ftl` files and checks for syntax errors without rendering (catches broken templates early)
- Consider adding a snapshot test: render a known fixture document and compare against a golden file to detect unintended template regressions
- Large-document rendering tests (15+ flows) should have generous timeout settings to avoid flaky failures on slower CI runners

## Verification

**Stage 23 sign-off requires all stages 1 through 23 to pass.** Run: `make verify`.

The verification report for stage 23 is `logs/stage-23.log`. It contains **cumulative output for stages 1–23** (Stage 1, then Stage 2, … then Stage 23 output).

| Check | How to verify | Pass criteria |
|---|---|---|
| FreeMarker config | Load template | Template loads without error |
| Document assembly | 3 synthesis results | ResearchDocument with 3 flow sections |
| Executive summary | Check summary | Counts match actual data |
| Risk matrix | Check table | All risks aggregated |
| Roadmap | Check phases | Ordered phases with flows |
| Markdown render | Render document | Valid Markdown string |
| TOC | Check generated TOC | Links to all flow sections |
| Mermaid diagrams | Check flow sections | Mermaid code blocks present |
| FreeMarker loops | 5 flows | 5 flow sections rendered |
| MinIO output | Publish document | Markdown + JSON in output bucket |
| Large document | 10+ flows | Renders without timeout |
| Template error | Invalid variable | Clear error message |

## Files to create

- `libs/publisher/build.gradle.kts`
- `libs/publisher/src/main/java/com/flowforge/publisher/config/FreeMarkerConfig.java`
- `libs/publisher/src/main/java/com/flowforge/publisher/model/ResearchDocument.java`
- `libs/publisher/src/main/java/com/flowforge/publisher/assembler/DocumentAssembler.java`
- `libs/publisher/src/main/java/com/flowforge/publisher/renderer/DocumentRenderer.java`
- `libs/publisher/src/main/java/com/flowforge/publisher/service/OutputPublisher.java`
- `libs/publisher/src/main/resources/templates/research-document.ftl`
- `libs/publisher/src/main/resources/templates/flow-section.ftl`
- `libs/publisher/src/main/resources/templates/risk-matrix.ftl`
- `libs/publisher/src/test/java/.../DocumentAssemblerTest.java`
- `libs/publisher/src/test/java/.../DocumentRendererTest.java`
- `libs/publisher/src/test/java/.../OutputPublisherIntegrationTest.java`

## Depends on

- Stage 22 (complete synthesis results)

## Produces

- `system-flows-research.md` — the final research document
- Structured JSON document in MinIO output bucket
- FreeMarker-rendered Markdown with TOC, tables, Mermaid diagrams
