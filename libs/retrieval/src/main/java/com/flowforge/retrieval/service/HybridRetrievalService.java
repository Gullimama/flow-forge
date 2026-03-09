package com.flowforge.retrieval.service;

import com.flowforge.retrieval.model.RankedDocument;
import com.flowforge.retrieval.model.RetrievalMetadata;
import com.flowforge.retrieval.model.RetrievalRequest;
import com.flowforge.retrieval.model.RetrievalResult;
import com.flowforge.retrieval.strategy.BM25Retriever;
import com.flowforge.retrieval.strategy.GraphRetriever;
import com.flowforge.retrieval.strategy.VectorRetriever;
import com.flowforge.retrieval.fusion.ReciprocalRankFusion;
import com.flowforge.reranker.resilient.ResilientReranker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

@Service
public class HybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    private final VectorRetriever vectorRetriever;
    private final BM25Retriever bm25Retriever;
    private final GraphRetriever graphRetriever;
    private final ReciprocalRankFusion rrfFusion;
    private final ResilientReranker reranker;
    private final MeterRegistry meterRegistry;

    public HybridRetrievalService(
            VectorRetriever vectorRetriever,
            BM25Retriever bm25Retriever,
            GraphRetriever graphRetriever,
            ReciprocalRankFusion rrfFusion,
            ResilientReranker reranker,
            MeterRegistry meterRegistry) {
        this.vectorRetriever = vectorRetriever;
        this.bm25Retriever = bm25Retriever;
        this.graphRetriever = graphRetriever;
        this.rrfFusion = rrfFusion;
        this.reranker = reranker;
        this.meterRegistry = meterRegistry;
    }

    public RetrievalResult retrieve(RetrievalRequest request) {
        long start = System.currentTimeMillis();

        List<RankedDocument> vectorCode = List.of();
        List<RankedDocument> vectorLog = List.of();
        List<RankedDocument> bm25Code = List.of();
        List<RankedDocument> bm25Log = List.of();
        List<RankedDocument> graphDocs = List.of();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var vc = CompletableFuture.supplyAsync(() -> vectorRetriever.retrieveCode(request), executor);
            var vl = request.scope() != RetrievalRequest.RetrievalScope.CODE
                ? CompletableFuture.supplyAsync(() -> vectorRetriever.retrieveLogs(request), executor)
                : CompletableFuture.completedFuture(List.<RankedDocument>of());
            var bc = CompletableFuture.supplyAsync(() -> bm25Retriever.retrieveCode(request), executor);
            var bl = request.scope() != RetrievalRequest.RetrievalScope.CODE
                ? CompletableFuture.supplyAsync(() -> bm25Retriever.retrieveLogs(request), executor)
                : CompletableFuture.completedFuture(List.<RankedDocument>of());
            var g = CompletableFuture.supplyAsync(() -> graphRetriever.retrieve(request), executor);

            vectorCode = vc.get();
            vectorLog = vl.get();
            bm25Code = bc.get();
            bm25Log = bl.get();
            graphDocs = g.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetrievalException("Retrieval interrupted", e);
        } catch (Exception e) {
            log.warn("Parallel retrieval failed, falling back to sequential: {}", e.getMessage());
            vectorCode = vectorRetriever.retrieveCode(request);
            vectorLog = request.scope() != RetrievalRequest.RetrievalScope.CODE ? vectorRetriever.retrieveLogs(request) : List.of();
            bm25Code = bm25Retriever.retrieveCode(request);
            bm25Log = request.scope() != RetrievalRequest.RetrievalScope.CODE ? bm25Retriever.retrieveLogs(request) : List.of();
            graphDocs = graphRetriever.retrieve(request);
        }

        var fusedDocs = rrfFusion.fuse(List.of(
            vectorCode, vectorLog, bm25Code, bm25Log, graphDocs
        ));

        var topCandidates = fusedDocs.stream().limit(request.topK() * 2).toList();
        var springDocs = topCandidates.stream()
            .map(d -> {
                var meta = new HashMap<>(d.metadata());
                meta.put("original_source", d.source().name());
                return new Document(d.content(), meta);
            })
            .toList();

        var reranked = reranker.rerank(request.query(), springDocs, request.topK());

        var finalDocs = reranked.stream()
            .map(d -> new RankedDocument(
                d.getText(),
                ((Number) d.getMetadata().getOrDefault("reranker_score", 0.0)).doubleValue(),
                resolveSource(d.getMetadata()),
                d.getMetadata()
            ))
            .toList();

        long latency = System.currentTimeMillis() - start;
        meterRegistry.timer("flowforge.retrieval.hybrid.latency").record(Duration.ofMillis(latency));

        return new RetrievalResult(
            request.query(),
            finalDocs,
            new RetrievalMetadata(
                vectorCode.size() + vectorLog.size(),
                bm25Code.size() + bm25Log.size(),
                graphDocs.size(),
                fusedDocs.size(),
                finalDocs.size(),
                latency
            )
        );
    }

    RankedDocument.DocumentSource resolveSource(Map<String, Object> metadata) {
        if (metadata == null) return RankedDocument.DocumentSource.VECTOR_CODE;
        var source = metadata.get("original_source");
        if (source instanceof RankedDocument.DocumentSource ds) return ds;
        if (source instanceof String s) {
            try {
                return RankedDocument.DocumentSource.valueOf(s);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return RankedDocument.DocumentSource.VECTOR_CODE;
    }
}
