package com.flowforge.eval.pipeline;

import com.flowforge.eval.embedding.EmbeddingTestCase;
import com.flowforge.eval.retrieval.RetrievalTestCase;
import java.util.List;

public record EvaluationDataset(
    List<RetrievalTestCase> retrievalCases,
    List<SynthesisCase> synthesisCases,
    List<EmbeddingTestCase> codeEmbeddingCases,
    List<EmbeddingTestCase> logEmbeddingCases
) {}

