package com.flowforge.flow;

import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.flow.model.FlowEvidence;
import com.flowforge.flow.model.FlowStep;
import com.flowforge.graph.query.Neo4jGraphQueryService;
import com.flowforge.parser.model.ReactiveComplexity;
import com.flowforge.retrieval.model.RankedDocument;
import com.flowforge.retrieval.model.RetrievalMetadata;
import com.flowforge.retrieval.model.RetrievalResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class FlowBuilderTestFixtures {

    public static final UUID SNAPSHOT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private FlowBuilderTestFixtures() {}

    public static List<List<Neo4jGraphQueryService.ChainNode>> callChain(String... serviceNames) {
        if (serviceNames.length == 0) return List.of();
        var chain = new java.util.ArrayList<Neo4jGraphQueryService.ChainNode>();
        for (int i = 0; i < serviceNames.length; i++) {
            chain.add(new Neo4jGraphQueryService.ChainNode(
                serviceNames[i],
                i > 0 ? "GET" : null,
                i > 0 ? "/path" : null,
                null
            ));
        }
        return List.of(chain);
    }

    public static RetrievalResult emptyRetrievalResult() {
        return new RetrievalResult(
            "",
            List.of(),
            new RetrievalMetadata(0, 0, 0, 0, 0, 0L)
        );
    }

    public static List<FlowStep> flowSteps(String... serviceNames) {
        var steps = new java.util.ArrayList<FlowStep>();
        for (int i = 0; i < serviceNames.length; i++) {
            steps.add(new FlowStep(
                i,
                serviceNames[i],
                "action",
                FlowStep.StepType.HTTP_CLIENT_CALL,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                ReactiveComplexity.NONE,
                Optional.empty()
            ));
        }
        return steps;
    }

    public static FlowStep flowStep(String serviceName, FlowStep.StepType stepType, ReactiveComplexity reactive) {
        return new FlowStep(
            0,
            serviceName,
            "action",
            stepType,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            reactive,
            Optional.empty()
        );
    }

    public static FlowCandidate flowCandidateWith(FlowEvidence evidence) {
        return new FlowCandidate(
            UUID.randomUUID(),
            SNAPSHOT_ID,
            "test-flow",
            FlowCandidate.FlowType.SYNC_REQUEST,
            flowSteps("a", "b"),
            List.of("a", "b"),
            evidence,
            0.0,
            FlowCandidate.FlowComplexity.MEDIUM
        );
    }
}
