package com.flowforge.eval.embedding;

import com.flowforge.eval.model.EvaluationResult;
import com.flowforge.vectorstore.service.VectorStoreService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingEvaluator {

    private final VectorStoreService vectorStore;
    private final EmbeddingModel embeddingModel;

    public EmbeddingEvaluator(
        VectorStoreService vectorStore,
        @org.springframework.beans.factory.annotation.Qualifier("codeEmbeddingModel")
        EmbeddingModel embeddingModel
    ) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Evaluate embedding quality by measuring cluster cohesion and separation.
     */
    public EvaluationResult.EmbeddingEvaluation evaluate(
        String collectionName,
        List<EmbeddingTestCase> testCases
    ) {
        var start = Instant.now();

        var embeddings = testCases.stream()
            .map(tc -> Map.entry(tc.label(), embeddingModel.embed(tc.text())))
            .toList();

        var byLabel = embeddings.stream()
            .collect(Collectors.groupingBy(Map.Entry::getKey,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

        double intraCohesion = byLabel.values().stream()
            .filter(group -> group.size() > 1)
            .mapToDouble(this::meanPairwiseSimilarity)
            .average().orElse(0.0);

        var labels = new ArrayList<>(byLabel.keySet());
        double interSeparation = 0.0;
        int pairs = 0;
        for (int i = 0; i < labels.size(); i++) {
            for (int j = i + 1; j < labels.size(); j++) {
                var group1 = byLabel.get(labels.get(i));
                var group2 = byLabel.get(labels.get(j));
                interSeparation += meanCrossGroupDistance(group1, group2);
                pairs++;
            }
        }
        interSeparation = pairs > 0 ? interSeparation / pairs : 0.0;

        double a = 1.0 - intraCohesion;
        double b = interSeparation;
        double silhouette = Math.max(a, b) == 0 ? 0.0 : (b - a) / Math.max(a, b);

        return new EvaluationResult.EmbeddingEvaluation(
            collectionName,
            intraCohesion,
            interSeparation,
            silhouette,
            testCases.size(),
            Duration.between(start, Instant.now())
        );
    }

    private double meanPairwiseSimilarity(List<float[]> group) {
        if (group.size() < 2) return 0.0;
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < group.size(); i++) {
            for (int j = i + 1; j < group.size(); j++) {
                sum += cosineSimilarity(group.get(i), group.get(j));
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private double meanCrossGroupDistance(List<float[]> g1, List<float[]> g2) {
        if (g1.isEmpty() || g2.isEmpty()) return 0.0;
        double sum = 0.0;
        int count = 0;
        for (float[] a : g1) {
            for (float[] b : g2) {
                sum += 1.0 - cosineSimilarity(a, b);
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}

