package com.flowforge.flow.builder;

import com.flowforge.flow.FlowBuilderTestFixtures;
import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.flow.model.FlowEvidence;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlowScoringTest {

    @Test
    void scoreConfidence_maxesAtOne() throws Exception {
        var evidence = new FlowEvidence(
            Collections.nCopies(20, "code"),
            Collections.nCopies(20, "log"),
            List.of("path"),
            List.of(),
            List.of(),
            java.util.Map.of()
        );
        var candidate = FlowBuilderTestFixtures.flowCandidateWith(evidence);
        var scored = invokeScoreConfidence(candidate);
        assertThat(scored.confidenceScore()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void scoreConfidence_zeroEvidence_givesZeroScore() throws Exception {
        var candidate = FlowBuilderTestFixtures.flowCandidateWith(FlowEvidence.empty());
        var scored = invokeScoreConfidence(candidate);
        assertThat(scored.confidenceScore()).isEqualTo(0.0);
    }

    @Test
    void scoreConfidence_codeSnippetsCappedAtPointFour() throws Exception {
        var evidence = new FlowEvidence(
            Collections.nCopies(100, "code"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            java.util.Map.of()
        );
        var scored = invokeScoreConfidence(FlowBuilderTestFixtures.flowCandidateWith(evidence));
        assertThat(scored.confidenceScore()).isEqualTo(0.4);
    }

    private static FlowCandidate invokeScoreConfidence(FlowCandidate candidate) throws Exception {
        var builder = new FlowCandidateBuilder(null, null, null, null);
        var method = FlowCandidateBuilder.class.getDeclaredMethod("scoreConfidence", FlowCandidate.class);
        method.setAccessible(true);
        return (FlowCandidate) method.invoke(builder, candidate);
    }
}
