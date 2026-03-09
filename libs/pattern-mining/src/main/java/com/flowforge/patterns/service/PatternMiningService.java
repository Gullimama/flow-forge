package com.flowforge.patterns.service;

import com.flowforge.common.client.MinioStorageClient;
import com.flowforge.common.client.OpenSearchClientWrapper;
import com.flowforge.logparser.model.ParsedLogEvent;
import com.flowforge.patterns.analysis.EnrichedPattern;
import com.flowforge.patterns.analysis.PatternAnalyzer;
import com.flowforge.patterns.extract.CallSequenceExtractor;
import com.flowforge.patterns.mining.SequencePatternMiner;
import com.flowforge.topology.service.TopologyEnrichmentService.TopologyGraph;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates sequence extraction, PrefixSpan mining, analysis, and evidence storage.
 */
@Service
public class PatternMiningService {

    private static final double MIN_SUPPORT = 0.03;

    private final CallSequenceExtractor sequenceExtractor;
    private final SequencePatternMiner miner;
    private final PatternAnalyzer analyzer;
    private final OpenSearchClientWrapper openSearch;
    private final MinioStorageClient minio;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public PatternMiningService(
            CallSequenceExtractor sequenceExtractor,
            SequencePatternMiner miner,
            PatternAnalyzer analyzer,
            OpenSearchClientWrapper openSearch,
            MinioStorageClient minio,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.sequenceExtractor = sequenceExtractor;
        this.miner = miner;
        this.analyzer = analyzer;
        this.openSearch = openSearch;
        this.minio = minio;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Mine patterns from a snapshot's log events and topology; store enriched patterns in MinIO.
     */
    public PatternMiningResult minePatterns(UUID snapshotId, List<ParsedLogEvent> events, TopologyGraph topology) {
        var traceSequences = sequenceExtractor.extractFromTraces(events, topology);
        var temporalSequences = sequenceExtractor.extractFromTemporalWindows(events, Duration.ofSeconds(30));

        var allSequences = new ArrayList<>(traceSequences);
        allSequences.addAll(temporalSequences);

        var patterns = miner.mine(allSequences, MIN_SUPPORT);
        var enrichedPatterns = analyzer.analyzePatterns(patterns, topology);

        minio.putJson("evidence", "patterns/" + snapshotId + ".json", enrichedPatterns);

        meterRegistry.counter("flowforge.patterns.discovered").increment(patterns.size());
        meterRegistry.counter("flowforge.patterns.error").increment(
            enrichedPatterns.stream().filter(EnrichedPattern::hasErrors).count());

        return new PatternMiningResult(
            traceSequences.size(),
            temporalSequences.size(),
            patterns.size(),
            enrichedPatterns.stream().filter(EnrichedPattern::crossesServiceBoundary).count(),
            enrichedPatterns.stream().filter(EnrichedPattern::hasErrors).count()
        );
    }
}
