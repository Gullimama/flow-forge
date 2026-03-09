package com.flowforge.eval.pipeline;

import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.retrieval.model.RankedDocument;
import com.flowforge.synthesis.model.FinalNarrativeOutput;
import java.util.List;

public record SynthesisCase(
    FlowCandidate flow,
    FinalNarrativeOutput synthesis,
    List<RankedDocument> evidence
) {}

