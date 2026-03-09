package com.flowforge.vectorstore.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Stub embedding model that returns fixed-dimension vectors (1024).
 * Used when no real EmbeddingModel (e.g. TEI) is configured; replace with real beans in Stage 15.
 */
public class StubEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSION = 1024;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<org.springframework.ai.embedding.Embedding> embeddings = request.getInstructions().stream()
            .map(ignored -> new org.springframework.ai.embedding.Embedding(generateVector(), 0))
            .collect(Collectors.toList());
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return generateVector();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return texts.stream().map(ignored -> generateVector()).collect(Collectors.toList());
    }

    private static float[] generateVector() {
        var v = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            v[i] = 0f;
        }
        return v;
    }
}
