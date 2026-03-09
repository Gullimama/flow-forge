package com.flowforge.eval.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.flowforge.vectorstore.service.VectorStoreService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

@ExtendWith(MockitoExtension.class)
class EmbeddingEvaluatorTest {

    @Mock
    VectorStoreService vectorStore;
    @Mock
    EmbeddingModel embeddingModel;

    @InjectMocks
    EmbeddingEvaluator evaluator;

    @Test
    @DisplayName("Cosine similarity of identical vectors is 1.0")
    void cosineSimilarity_identical() {
        float[] vec = {1.0f, 2.0f, 3.0f};
        when(embeddingModel.embed(anyString())).thenReturn(vec);

        var testCases = List.of(
            new EmbeddingTestCase("cluster-a", "text1"),
            new EmbeddingTestCase("cluster-a", "text2")
        );

        var eval = evaluator.evaluate("code-embeddings", testCases);

        assertThat(eval.intraCohesion()).isCloseTo(1.0, within(1e-6));
    }
}

