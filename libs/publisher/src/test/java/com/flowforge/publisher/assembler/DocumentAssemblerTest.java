package com.flowforge.publisher.assembler;

import com.flowforge.publisher.model.FlowSection;
import com.flowforge.publisher.model.RoadmapPhase;
import com.flowforge.synthesis.TestFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class DocumentAssemblerTest {

    private final DocumentAssembler assembler = new DocumentAssembler();

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
