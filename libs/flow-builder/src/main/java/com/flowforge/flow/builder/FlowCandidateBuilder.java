package com.flowforge.flow.builder;

import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.flow.model.FlowEvidence;
import com.flowforge.flow.model.FlowStep;
import com.flowforge.graph.query.Neo4jGraphQueryService;
import com.flowforge.parser.model.ReactiveComplexity;
import com.flowforge.retrieval.model.RankedDocument;
import com.flowforge.retrieval.model.RetrievalRequest;
import com.flowforge.retrieval.model.RetrievalResult;
import com.flowforge.retrieval.service.HybridRetrievalService;
import com.flowforge.common.client.MinioStorageClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class FlowCandidateBuilder {

    private final Neo4jGraphQueryService graphQuery;
    private final HybridRetrievalService retrieval;
    private final MinioStorageClient minio;
    private final MeterRegistry meterRegistry;

    public FlowCandidateBuilder(
            Neo4jGraphQueryService graphQuery,
            HybridRetrievalService retrieval,
            MinioStorageClient minio,
            MeterRegistry meterRegistry) {
        this.graphQuery = graphQuery;
        this.retrieval = retrieval;
        this.minio = minio;
        this.meterRegistry = meterRegistry;
    }

    public List<FlowCandidate> buildCandidates(UUID snapshotId) {
        var candidates = new ArrayList<FlowCandidate>();

        candidates.addAll(buildHttpFlows(snapshotId));
        candidates.addAll(buildKafkaFlows(snapshotId));
        candidates.addAll(buildErrorFlows(snapshotId));
        candidates.addAll(buildPatternFlows(snapshotId));

        var merged = mergeOverlappingFlows(candidates);

        var enriched = merged.stream()
            .map(c -> enrichWithEvidence(c, snapshotId))
            .toList();

        var scored = enriched.stream()
            .map(this::scoreConfidence)
            .sorted(Comparator.comparingDouble(FlowCandidate::confidenceScore).reversed())
            .toList();

        minio.putJson("evidence", "flow-candidates/" + snapshotId + ".json", scored);
        meterRegistry.counter("flowforge.flow.candidates.built").increment(scored.size());

        return scored;
    }

    private List<FlowCandidate> buildHttpFlows(UUID snapshotId) {
        var entryPoints = graphQuery.findEntryPointServices();
        var flows = new ArrayList<FlowCandidate>();

        for (var entry : entryPoints) {
            var chains = graphQuery.getCallChainsFrom(entry, 5);
            for (var chain : chains) {
                var steps = new ArrayList<FlowStep>();
                for (int i = 0; i < chain.size(); i++) {
                    steps.add(chainNodeToFlowStep(i, chain.get(i)));
                }
                if (steps.size() >= 2) {
                    flows.add(new FlowCandidate(
                        UUID.randomUUID(), snapshotId,
                        generateFlowName(steps),
                        FlowCandidate.FlowType.SYNC_REQUEST,
                        steps,
                        steps.stream().map(FlowStep::serviceName).distinct().toList(),
                        FlowEvidence.empty(),
                        0.0,
                        assessComplexity(steps)
                    ));
                }
            }
        }
        return flows;
    }

    private List<FlowCandidate> buildKafkaFlows(UUID snapshotId) {
        var topics = graphQuery.findKafkaTopicsWithConnections();
        var flows = new ArrayList<FlowCandidate>();

        for (var topic : topics) {
            String name = (String) topic.get("name");
            @SuppressWarnings("unchecked")
            var producers = (List<String>) topic.getOrDefault("producers", List.of());
            @SuppressWarnings("unchecked")
            var consumers = (List<String>) topic.getOrDefault("consumers", List.of());
            if (name == null || (producers.isEmpty() && consumers.isEmpty())) continue;

            var steps = new ArrayList<FlowStep>();
            int order = 0;
            for (var p : producers) {
                steps.add(new FlowStep(order++, p, "PRODUCE " + name, FlowStep.StepType.KAFKA_PRODUCE,
                    Optional.empty(), Optional.empty(), Optional.of(name), List.of(), ReactiveComplexity.NONE, Optional.empty()));
            }
            for (var c : consumers) {
                steps.add(new FlowStep(order++, c, "CONSUME " + name, FlowStep.StepType.KAFKA_CONSUME,
                    Optional.empty(), Optional.empty(), Optional.of(name), List.of(), ReactiveComplexity.NONE, Optional.empty()));
            }
            if (steps.size() >= 2) {
                flows.add(new FlowCandidate(
                    UUID.randomUUID(), snapshotId,
                    "kafka-" + (name != null ? name : "flow"),
                    FlowCandidate.FlowType.ASYNC_EVENT,
                    steps,
                    steps.stream().map(FlowStep::serviceName).distinct().toList(),
                    FlowEvidence.empty(),
                    0.0,
                    assessComplexity(steps)
                ));
            }
        }
        return flows;
    }

    private List<FlowCandidate> buildErrorFlows(UUID snapshotId) {
        return List.of();
    }

    private List<FlowCandidate> buildPatternFlows(UUID snapshotId) {
        return List.of();
    }

    private FlowCandidate enrichWithEvidence(FlowCandidate candidate, UUID snapshotId) {
        var codeSnippets = new ArrayList<String>();
        var logPatterns = new ArrayList<String>();

        var combinedQuery = candidate.steps().stream()
            .map(this::buildRetrievalQuery)
            .collect(Collectors.joining(" | "));

        var result = retrieval.retrieve(new RetrievalRequest(
            snapshotId, combinedQuery, RetrievalRequest.RetrievalScope.BOTH,
            Math.max(10, candidate.steps().size() * 3),
            Optional.empty(), Optional.of(2)
        ));

        for (var doc : result.documents()) {
            if (doc.source() == RankedDocument.DocumentSource.VECTOR_CODE
                || doc.source() == RankedDocument.DocumentSource.BM25_CODE) {
                codeSnippets.add(doc.content());
            } else {
                logPatterns.add(doc.content());
            }
        }

        var evidence = new FlowEvidence(
            codeSnippets, logPatterns, List.of(), List.of(), List.of(), Map.of());

        return new FlowCandidate(
            candidate.candidateId(), candidate.snapshotId(),
            candidate.flowName(), candidate.flowType(),
            candidate.steps(), candidate.involvedServices(),
            evidence,
            candidate.confidenceScore(),
            candidate.complexity()
        );
    }

    private FlowCandidate scoreConfidence(FlowCandidate candidate) {
        double score = 0.0;
        var ev = candidate.evidence();
        score += Math.min(ev.codeSnippets().size() * 0.1, 0.4);
        score += Math.min(ev.logPatterns().size() * 0.05, 0.2);
        score += Math.min(ev.sequencePatterns().size() * 0.15, 0.3);
        if (!ev.graphPaths().isEmpty()) score += 0.1;

        return new FlowCandidate(
            candidate.candidateId(), candidate.snapshotId(),
            candidate.flowName(), candidate.flowType(),
            candidate.steps(), candidate.involvedServices(),
            candidate.evidence(),
            Math.min(score, 1.0),
            candidate.complexity()
        );
    }

    FlowCandidate.FlowComplexity assessComplexity(List<FlowStep> steps) {
        int serviceCount = (int) steps.stream().map(FlowStep::serviceName).distinct().count();
        boolean hasReactive = steps.stream()
            .anyMatch(s -> s.reactiveComplexity() != ReactiveComplexity.NONE);
        boolean hasAsync = steps.stream()
            .anyMatch(s -> s.stepType() == FlowStep.StepType.KAFKA_PRODUCE || s.stepType() == FlowStep.StepType.KAFKA_CONSUME);

        if (serviceCount >= 5 || (hasReactive && hasAsync)) return FlowCandidate.FlowComplexity.VERY_HIGH;
        if (serviceCount >= 3 || hasAsync) return FlowCandidate.FlowComplexity.HIGH;
        if (serviceCount >= 2) return FlowCandidate.FlowComplexity.MEDIUM;
        return FlowCandidate.FlowComplexity.LOW;
    }

    private String generateFlowName(List<FlowStep> steps) {
        if (steps.isEmpty()) return "unknown-flow";
        String first = steps.get(0).serviceName();
        String last = steps.get(steps.size() - 1).serviceName();
        return first + "-to-" + last;
    }

    private FlowStep chainNodeToFlowStep(int order, Neo4jGraphQueryService.ChainNode node) {
        String action = (node.httpMethod() != null ? node.httpMethod() + " " : "")
            + (node.path() != null ? node.path() : "").trim();
        if (action.isEmpty()) action = node.serviceName();
        var stepType = order == 0 ? FlowStep.StepType.HTTP_ENDPOINT : FlowStep.StepType.HTTP_CLIENT_CALL;
        return new FlowStep(
            order,
            node.serviceName(),
            action,
            stepType,
            Optional.empty(),
            Optional.ofNullable(node.methodName()),
            Optional.empty(),
            List.of(),
            ReactiveComplexity.NONE,
            Optional.empty()
        );
    }

    private String buildRetrievalQuery(FlowStep step) {
        return "%s %s %s".formatted(
            step.serviceName(),
            step.methodName().orElse(""),
            step.action() != null ? step.action() : ""
        ).trim();
    }

    private List<FlowCandidate> mergeOverlappingFlows(List<FlowCandidate> candidates) {
        var merged = new ArrayList<FlowCandidate>();
        var used = new HashSet<Integer>();
        for (int i = 0; i < candidates.size(); i++) {
            if (used.contains(i)) continue;
            var current = candidates.get(i);
            var currentServices = new HashSet<>(current.involvedServices());
            for (int j = i + 1; j < candidates.size(); j++) {
                if (used.contains(j)) continue;
                var other = candidates.get(j);
                var otherServices = new HashSet<>(other.involvedServices());
                long overlap = currentServices.stream().filter(otherServices::contains).count();
                if (overlap >= Math.min(currentServices.size(), otherServices.size()) * 0.7) {
                    used.add(j);
                }
            }
            merged.add(current);
        }
        return merged;
    }
}
