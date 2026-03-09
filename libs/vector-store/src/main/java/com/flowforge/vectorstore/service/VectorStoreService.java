package com.flowforge.vectorstore.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.Condition;
import io.qdrant.client.grpc.Points.FieldCondition;
import io.qdrant.client.grpc.Points.Match;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class VectorStoreService {

    private final VectorStore codeVectorStore;
    private final VectorStore logVectorStore;
    private final QdrantClient qdrantClient;
    private final MeterRegistry meterRegistry;

    public VectorStoreService(
            @Qualifier("codeVectorStore") VectorStore codeVectorStore,
            @Qualifier("logVectorStore") VectorStore logVectorStore,
            QdrantClient qdrantClient,
            MeterRegistry meterRegistry) {
        this.codeVectorStore = codeVectorStore;
        this.logVectorStore = logVectorStore;
        this.qdrantClient = qdrantClient;
        this.meterRegistry = meterRegistry;
    }

    public void addCodeDocuments(List<Document> documents) {
        meterRegistry.timer("flowforge.vectorstore.code.add").record(() -> codeVectorStore.add(documents));
    }

    public void addLogDocuments(List<Document> documents) {
        meterRegistry.timer("flowforge.vectorstore.log.add").record(() -> logVectorStore.add(documents));
    }

    public List<Document> searchCode(String query, int topK, String snapshotId) {
        var request = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .similarityThreshold(0.5)
            .filterExpression(new FilterExpressionBuilder().eq("snapshot_id", snapshotId).build())
            .build();
        return meterRegistry.timer("flowforge.vectorstore.code.search").record(() -> codeVectorStore.similaritySearch(request));
    }

    public List<Document> searchLogs(String query, int topK, String snapshotId, Optional<String> serviceName) {
        var b = new FilterExpressionBuilder();
        Filter.Expression filter = serviceName
            .map(s -> b.and(b.eq("snapshot_id", snapshotId), b.eq("service_name", s)).build())
            .orElseGet(() -> b.eq("snapshot_id", snapshotId).build());
        var request = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .similarityThreshold(0.5)
            .filterExpression(filter)
            .build();
        return meterRegistry.timer("flowforge.vectorstore.log.search").record(() -> logVectorStore.similaritySearch(request));
    }

    public void deleteBySnapshot(String snapshotId) {
        var filter = io.qdrant.client.grpc.Points.Filter.newBuilder()
            .addMust(Condition.newBuilder()
                .setField(FieldCondition.newBuilder()
                    .setKey("snapshot_id")
                    .setMatch(Match.newBuilder().setKeyword(snapshotId).build())
                    .build())
                .build())
            .build();
        try {
            qdrantClient.deleteAsync("code-embeddings", filter).get(30, java.util.concurrent.TimeUnit.SECONDS);
            qdrantClient.deleteAsync("log-embeddings", filter).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete by snapshot " + snapshotId, e);
        }
    }

}
