package com.flowforge.publisher;

import com.flowforge.publisher.assembler.DocumentAssembler;
import com.flowforge.publisher.model.ResearchDocument;
import com.flowforge.synthesis.model.SynthesisFullResult;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Test fixtures for publisher tests.
 */
public final class TestFixtures {

    private static final UUID SNAPSHOT_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    private TestFixtures() {}

    /**
     * Build a research document with the given number of flow sections using the assembler.
     */
    public static ResearchDocument sampleResearchDocument(int flowCount) {
        var results = flowCount == 0
            ? List.<SynthesisFullResult>of()
            : IntStream.rangeClosed(1, flowCount)
                .mapToObj(i -> com.flowforge.synthesis.TestFixtures.sampleFullResult("flow-" + i))
                .toList();
        return new DocumentAssembler().assemble(SNAPSHOT_ID, results);
    }

    /**
     * Research document that includes at least one flow section with Mermaid diagrams.
     */
    public static ResearchDocument sampleResearchDocumentWithDiagrams() {
        return new DocumentAssembler().assemble(
            SNAPSHOT_ID,
            List.of(com.flowforge.synthesis.TestFixtures.sampleFullResult("booking-creation-flow"))
        );
    }
}
