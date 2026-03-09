package com.flowforge.eval.retrieval;

import com.flowforge.eval.model.EvaluationResult;
import com.flowforge.retrieval.model.RankedDocument;
import com.flowforge.retrieval.model.RetrievalRequest;
import com.flowforge.retrieval.model.RetrievalResult;
import com.flowforge.retrieval.service.HybridRetrievalService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class RetrievalEvaluator {

    private final HybridRetrievalService retrieval;

    public RetrievalEvaluator(HybridRetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    /**
     * Evaluate retrieval quality over a set of test cases.
     */
    public List<EvaluationResult.RetrievalEvaluation> evaluate(List<RetrievalTestCase> testCases) {
        return testCases.stream().map(tc -> {
            var start = Instant.now();
            RetrievalResult result = retrieval.retrieve(
                new RetrievalRequest(
                    java.util.UUID.randomUUID(),
                    tc.query(),
                    RetrievalRequest.RetrievalScope.BOTH,
                    tc.topK(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty()));
            var latency = Duration.between(start, Instant.now());

            var retrievedIds = result.documents().stream()
                .map(this::documentId)
                .collect(Collectors.toSet());

            long relevantRetrieved = retrievedIds.stream()
                .filter(tc.relevantIds()::contains)
                .count();

            double precision = retrievedIds.isEmpty() ? 0.0 :
                (double) relevantRetrieved / retrievedIds.size();
            double recall = tc.relevantIds().isEmpty() ? 0.0 :
                (double) relevantRetrieved / tc.relevantIds().size();
            double f1 = (precision + recall) == 0 ? 0.0 :
                2 * precision * recall / (precision + recall);

            double mrr = computeMRR(result, tc.relevantIds());
            double ndcg = computeNDCG(result, tc.relevantIds(), tc.topK());

            return new EvaluationResult.RetrievalEvaluation(
                tc.queryId(), retrievedIds.size(), (int) relevantRetrieved,
                precision, recall, f1, mrr, ndcg, latency
            );
        }).toList();
    }

    private String documentId(RankedDocument doc) {
        Object id = doc.metadata() != null ? doc.metadata().get("doc_id") : null;
        return id != null ? id.toString() : Integer.toHexString(doc.content().hashCode());
    }

    private double computeMRR(RetrievalResult result, Set<String> relevant) {
        var docs = result.documents();
        for (int i = 0; i < docs.size(); i++) {
            if (relevant.contains(documentId(docs.get(i)))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private double computeNDCG(RetrievalResult result, Set<String> relevant, int k) {
        var docs = result.documents();
        double dcg = 0.0;
        double idcg = 0.0;
        for (int i = 0; i < Math.min(docs.size(), k); i++) {
            double rel = relevant.contains(documentId(docs.get(i))) ? 1.0 : 0.0;
            dcg += rel / (Math.log(i + 2) / Math.log(2));
        }
        for (int i = 0; i < Math.min(relevant.size(), k); i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0 ? 0.0 : dcg / idcg;
    }
}


