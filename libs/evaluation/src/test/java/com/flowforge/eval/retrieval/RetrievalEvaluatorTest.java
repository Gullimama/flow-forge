package com.flowforge.eval.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.flowforge.eval.model.EvaluationResult;
import com.flowforge.eval.retrieval.RetrievalTestCase;
import com.flowforge.retrieval.model.RankedDocument;
import com.flowforge.retrieval.model.RetrievalMetadata;
import com.flowforge.retrieval.model.RetrievalRequest;
import com.flowforge.retrieval.model.RetrievalResult;
import com.flowforge.retrieval.service.HybridRetrievalService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetrievalEvaluatorTest {

    @Mock
    HybridRetrievalService retrievalService;

    @InjectMocks
    RetrievalEvaluator evaluator;

    @Test
    @DisplayName("Precision: 3 relevant out of 5 retrieved = 0.6")
    void precision_threeOfFive() {
        var docs = List.of(
            result("doc-1"), result("doc-2"), result("doc-3"),
            result("doc-4"), result("doc-5"));
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
            .thenReturn(new RetrievalResult("q1", docs, dummyMeta()));

        var testCase = new RetrievalTestCase(
            "q1", "test query", Set.of("doc-1", "doc-3", "doc-5"), 5);
        EvaluationResult.RetrievalEvaluation eval = evaluator.evaluate(List.of(testCase)).getFirst();

        assertThat(eval.precision()).isCloseTo(0.6, within(0.001));
        assertThat(eval.relevantCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Recall: 3 found out of 10 relevant = 0.3")
    void recall_threeOfTen() {
        var docs = List.of(result("doc-1"), result("doc-2"), result("doc-3"));
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
            .thenReturn(new RetrievalResult("q1", docs, dummyMeta()));

        var relevant = IntStream.rangeClosed(1, 10)
            .mapToObj(i -> "doc-" + i).collect(Collectors.toSet());
        var testCase = new RetrievalTestCase("q1", "test query", relevant, 10);
        var eval = evaluator.evaluate(List.of(testCase)).getFirst();

        assertThat(eval.recall()).isCloseTo(0.3, within(0.001));
    }

    @Test
    @DisplayName("F1: harmonic mean of precision and recall")
    void f1_harmonicMean() {
        var docs = List.of(result("doc-1"), result("doc-2"));
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
            .thenReturn(new RetrievalResult("q1", docs, dummyMeta()));

        var testCase = new RetrievalTestCase(
            "q1", "query", Set.of("doc-1", "doc-3", "doc-4"), 5);
        var eval = evaluator.evaluate(List.of(testCase)).getFirst();

        double expectedP = 1.0 / 2.0;
        double expectedR = 1.0 / 3.0;
        double expectedF1 = 2 * expectedP * expectedR / (expectedP + expectedR);
        assertThat(eval.f1()).isCloseTo(expectedF1, within(0.001));
    }

    @Test
    @DisplayName("MRR: first relevant at position 3 = 1/3")
    void mrr_firstRelevantAtThird() {
        var docs = List.of(result("doc-a"), result("doc-b"), result("doc-c"));
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
            .thenReturn(new RetrievalResult("q1", docs, dummyMeta()));

        var testCase = new RetrievalTestCase("q1", "query", Set.of("doc-c"), 5);
        var eval = evaluator.evaluate(List.of(testCase)).getFirst();

        assertThat(eval.mrr()).isCloseTo(1.0 / 3.0, within(0.001));
    }

    @Test
    @DisplayName("NDCG: perfect ranking yields 1.0")
    void ndcg_perfectRanking() {
        var docs = List.of(result("r1"), result("r2"), result("r3"));
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
            .thenReturn(new RetrievalResult("q1", docs, dummyMeta()));

        var testCase = new RetrievalTestCase(
            "q1", "query", Set.of("r1", "r2", "r3"), 3);
        var eval = evaluator.evaluate(List.of(testCase)).getFirst();

        assertThat(eval.ndcg()).isCloseTo(1.0, within(0.001));
    }

    @Test
    @DisplayName("Empty result set yields all-zero metrics")
    void emptyResults_allZero() {
        when(retrievalService.retrieve(any(RetrievalRequest.class)))
            .thenReturn(new RetrievalResult("q1", List.of(), dummyMeta()));

        var testCase = new RetrievalTestCase("q1", "query", Set.of("doc-1"), 5);
        var eval = evaluator.evaluate(List.of(testCase)).getFirst();

        assertThat(eval.precision()).isZero();
        assertThat(eval.recall()).isZero();
        assertThat(eval.f1()).isZero();
        assertThat(eval.mrr()).isZero();
    }

    private RankedDocument result(String docId) {
        return new RankedDocument("content-" + docId, 0.9,
            RankedDocument.DocumentSource.VECTOR_CODE, Map.of("doc_id", docId));
    }

    private RetrievalMetadata dummyMeta() {
        return new RetrievalMetadata(0, 0, 0, 0, 0, 0);
    }
}

