package com.flowforge.synthesis;

import com.flowforge.anomaly.episode.AnomalyEpisodeBuilder;
import com.flowforge.flow.model.FlowCandidate;
import com.flowforge.flow.model.FlowEvidence;
import com.flowforge.flow.model.FlowStep;
import com.flowforge.parser.model.ReactiveComplexity;
import com.flowforge.patterns.analysis.EnrichedPattern;
import com.flowforge.synthesis.model.BuildDependency;
import com.flowforge.synthesis.model.CodeArtifactExplanation;
import com.flowforge.synthesis.model.CodeExplanationOutput;
import com.flowforge.synthesis.model.CouplingPoint;
import com.flowforge.synthesis.model.DataFlowDescription;
import com.flowforge.synthesis.model.DependencyConflict;
import com.flowforge.synthesis.model.DependencyEdge;
import com.flowforge.synthesis.model.DependencyGraph;
import com.flowforge.synthesis.model.DependencyMappingOutput;
import com.flowforge.synthesis.model.DiagramSpec;
import com.flowforge.synthesis.model.EstimatedEffort;
import com.flowforge.synthesis.model.FinalNarrativeOutput;
import com.flowforge.synthesis.model.FlowAnalysisOutput;
import com.flowforge.synthesis.model.InteractionStep;
import com.flowforge.synthesis.model.KeyFinding;
import com.flowforge.synthesis.model.MigrationPhase;
import com.flowforge.synthesis.model.MigrationPlanOutput;
import com.flowforge.synthesis.model.MigrationRisk;
import com.flowforge.synthesis.model.ReactivePatternExplanation;
import com.flowforge.synthesis.model.RiskAssessmentOutput;
import com.flowforge.synthesis.model.RollbackPlan;
import com.flowforge.synthesis.model.RuntimeDependency;
import com.flowforge.synthesis.model.SharedLibrary;
import com.flowforge.synthesis.model.SynthesisFullResult;
import com.flowforge.synthesis.model.SynthesisPartialResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Test fixtures for synthesis stages 1–3.
 */
public final class TestFixtures {

    private static final UUID SNAPSHOT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private TestFixtures() {}

    public static FlowCandidate httpFlowCandidate() {
        return new FlowCandidate(
            UUID.randomUUID(),
            SNAPSHOT_ID,
            "booking-creation-flow",
            FlowCandidate.FlowType.SYNC_REQUEST,
            List.of(
                new FlowStep(0, "api-gateway", "GET /bookings", FlowStep.StepType.HTTP_ENDPOINT,
                    Optional.of("com.example.GatewayController"), Optional.of("createBooking"),
                    Optional.empty(), List.of("@Controller"), ReactiveComplexity.NONE, Optional.empty()),
                new FlowStep(1, "booking-service", "POST /orders", FlowStep.StepType.HTTP_CLIENT_CALL,
                    Optional.of("com.example.BookingService"), Optional.of("placeOrder"),
                    Optional.empty(), List.of("@Client"), ReactiveComplexity.LINEAR, Optional.empty()),
                new FlowStep(2, "inventory-service", "GET /stock", FlowStep.StepType.HTTP_CLIENT_CALL,
                    Optional.of("com.example.InventoryClient"), Optional.of("checkStock"),
                    Optional.empty(), List.of(), ReactiveComplexity.NONE, Optional.empty())
            ),
            List.of("api-gateway", "booking-service", "inventory-service"),
            new FlowEvidence(
                List.of("class GatewayController { ... }", "class BookingService { ... }"),
                List.of("ERROR: Connection refused", "INFO: Order created"),
                List.of("api-gateway -> booking-service -> inventory-service"),
                List.<EnrichedPattern>of(),
                List.<AnomalyEpisodeBuilder.AnomalyEpisode>of(),
                java.util.Map.of()
            ),
            0.85,
            FlowCandidate.FlowComplexity.MEDIUM
        );
    }

    public static FlowCandidate kafkaFlowCandidate() {
        return new FlowCandidate(
            UUID.randomUUID(),
            SNAPSHOT_ID,
            "order-events-flow",
            FlowCandidate.FlowType.ASYNC_EVENT,
            List.of(
                new FlowStep(0, "order-service", "publish", FlowStep.StepType.KAFKA_PRODUCE,
                    Optional.of("com.example.OrderPublisher"), Optional.of("publish"),
                    Optional.of("orders"), List.of("@KafkaClient"), ReactiveComplexity.BRANCHING, Optional.empty()),
                new FlowStep(1, "inventory-service", "consume", FlowStep.StepType.KAFKA_CONSUME,
                    Optional.of("com.example.OrderConsumer"), Optional.of("onOrder"),
                    Optional.of("orders"), List.of("@KafkaListener"), ReactiveComplexity.BRANCHING, Optional.empty())
            ),
            List.of("order-service", "inventory-service"),
            new FlowEvidence(
                List.of("@KafkaClient OrderEvent", "@KafkaListener void onOrder"),
                List.of("Kafka consumer lag"),
                List.of("order-service -[PRODUCES_TO]-> orders"),
                List.<EnrichedPattern>of(),
                List.<AnomalyEpisodeBuilder.AnomalyEpisode>of(),
                java.util.Map.of()
            ),
            0.7,
            FlowCandidate.FlowComplexity.HIGH
        );
    }

    public static FlowCandidate grpcFlowCandidate() {
        return new FlowCandidate(
            UUID.randomUUID(),
            SNAPSHOT_ID,
            "grpc-lookup-flow",
            FlowCandidate.FlowType.SYNC_REQUEST,
            List.of(
                new FlowStep(0, "gateway", "gRPC lookup", FlowStep.StepType.EXTERNAL_CALL,
                    Optional.of("com.example.GrpcClient"), Optional.of("lookup"),
                    Optional.empty(), List.of(), ReactiveComplexity.LINEAR, Optional.empty())
            ),
            List.of("gateway"),
            FlowEvidence.empty(),
            0.5,
            FlowCandidate.FlowComplexity.LOW
        );
    }

    public static FlowAnalysisOutput sampleFlowAnalysisOutput() {
        return new FlowAnalysisOutput(
            "booking-creation-flow",
            "Create a booking across gateway, booking and inventory services.",
            "HTTP POST to /bookings",
            List.of(
                new InteractionStep(1, "api-gateway", "booking-service", "HTTP", "Forward request", "BookingRequest"),
                new InteractionStep(2, "booking-service", "inventory-service", "HTTP", "Check stock", "StockQuery"),
                new InteractionStep(3, "inventory-service", "booking-service", "HTTP", "Return stock", "StockResponse")
            ),
            new DataFlowDescription(
                "BookingRequest (userId, productId, quantity)",
                "BookingResponse (orderId, status)",
                List.of("Validate input", "Reserve inventory", "Create order"),
                List.of("DB write", "Cache invalidate")
            ),
            List.of("PostgreSQL", "Redis"),
            List.of("Inventory is eventually consistent")
        );
    }

    public static CodeExplanationOutput sampleCodeExplanationOutput() {
        return new CodeExplanationOutput(
            "booking-creation-flow",
            List.of(
                new CodeArtifactExplanation(
                    "api-gateway", "com.example.GatewayController", "createBooking",
                    "Entry point", "Receives HTTP and forwards to booking-service",
                    List.of("@Controller", "@Client"), "Low complexity"),
                new CodeArtifactExplanation(
                    "booking-service", "com.example.BookingService", "placeOrder",
                    "Orchestrator", "Calls inventory and persists order",
                    List.of("@Singleton", "@Client"), "Medium - reactive chain")
            ),
            List.of(
                new ReactivePatternExplanation(
                    "BookingService.placeOrder", "flatMap -> map -> block",
                    "RxJava chain for inventory call", "MEDIUM", "Replace with virtual threads")
            ),
            List.of("Gateway", "Repository"),
            List.of("@Client", "@KafkaListener")
        );
    }

    public static RiskAssessmentOutput sampleRiskAssessmentOutput() {
        return new RiskAssessmentOutput(
            "booking-creation-flow",
            RiskAssessmentOutput.RiskLevel.HIGH,
            List.of(
                new MigrationRisk("REACTIVE", "RxJava block() in request path",
                    RiskAssessmentOutput.RiskLevel.HIGH, "booking-service", "Migrate to reactive or virtual threads"),
                new MigrationRisk("COUPLING", "Tight sync between booking and inventory",
                    RiskAssessmentOutput.RiskLevel.MEDIUM, "booking-service", "Introduce async or saga")
            ),
            List.of(
                new CouplingPoint("booking-service", "inventory-service", "SYNC_HTTP",
                    "Direct HTTP call on every order", "Event-driven or cache")
            ),
            List.of("Blocking call in gateway"),
            List.of("Prioritize inventory-service migration", "Add circuit breaker")
        );
    }

    // --- Stage 4–6 and pipeline fixtures ---

    public static SynthesisPartialResult samplePartialResult() {
        return new SynthesisPartialResult(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            sampleFlowAnalysisOutput(),
            sampleCodeExplanationOutput(),
            sampleRiskAssessmentOutput()
        );
    }

    public static DependencyMappingOutput sampleDependencyMappingOutput() {
        return new DependencyMappingOutput(
            "booking-creation-flow",
            List.of(
                new RuntimeDependency("booking-service", "PostgreSQL", "14", "Orders DB",
                    RuntimeDependency.DependencyType.DATABASE),
                new RuntimeDependency("api-gateway", "Redis", "7", "Session cache",
                    RuntimeDependency.DependencyType.CACHE)
            ),
            List.of(
                new BuildDependency("booking-service", "io.micronaut", "micronaut-http-client", "4.0.0", "compile"),
                new BuildDependency("api-gateway", "io.micronaut", "micronaut-http-client", "4.0.0", "compile"),
                new BuildDependency("inventory-service", "org.projectlombok", "lombok", "1.18.30", "compileOnly")
            ),
            List.of(
                new DependencyConflict("micronaut-core", "booking-service", "4.0.0",
                    "inventory-service", "3.7.0", "Align to 4.0.0")
            ),
            List.of(
                new SharedLibrary("common-utils", "2.1", List.of("booking-service", "inventory-service"),
                    "Low impact")
            ),
            new DependencyGraph(
                List.of("api-gateway", "booking-service", "inventory-service", "PostgreSQL", "Redis"),
                List.of(
                    new DependencyEdge("booking-service", "PostgreSQL", "JDBC"),
                    new DependencyEdge("api-gateway", "Redis", "cache")
                )
            )
        );
    }

    public static DependencyMappingOutput dependencyOutputWithConflicts() {
        return new DependencyMappingOutput(
            "multi-version-flow",
            List.of(),
            List.of(),
            List.of(
                new DependencyConflict("spring-boot", "svc-a", "3.2.0", "svc-b", "2.7.0", "Upgrade svc-b to 3.2"),
                new DependencyConflict("jackson-databind", "svc-a", "2.15.0", "svc-b", "2.13.0", "Unify to 2.15")
            ),
            List.of(),
            new DependencyGraph(List.of(), List.of())
        );
    }

    public static MigrationPlanOutput sampleMigrationPlanOutput() {
        return new MigrationPlanOutput(
            "booking-creation-flow",
            MigrationPlanOutput.MigrationStrategy.STRANGLER_FIG,
            List.of(
                new MigrationPhase(1, "Inventory first", "Migrate inventory-service", List.of("inventory-service"),
                    List.of("Extract API", "Add feature flag"), List.of("New API module"), "2 weeks", List.of("Rollback complexity")),
                new MigrationPhase(2, "Booking service", "Migrate booking-service", List.of("booking-service"),
                    List.of("Replace client", "Virtual threads"), List.of("Updated service"), "3 weeks", List.of()),
                new MigrationPhase(3, "Gateway", "Migrate gateway", List.of("api-gateway"),
                    List.of("Route to new flow"), List.of("Cutover"), "1 week", List.of())
            ),
            List.of("Feature flags enabled", "Staging env"),
            new EstimatedEffort("6 weeks", 2, "MEDIUM", Map.of("inventory-service", "2w", "booking-service", "3w", "api-gateway", "1w")),
            List.of("Contract tests", "Load test"),
            new RollbackPlan("Feature flag revert", List.of("Disable new path", "Restore traffic"),
                "DB backup before each phase", "Strangler routing")
        );
    }

    public static FinalNarrativeOutput sampleFinalNarrativeOutput() {
        return new FinalNarrativeOutput(
            "booking-creation-flow",
            "Three-service sync flow with reactive patterns; recommend strangler migration.",
            "The booking-creation-flow spans api-gateway, booking-service, and inventory-service...",
            List.of(
                new DiagramSpec("Booking flow sequence", DiagramSpec.DiagramType.SEQUENCE,
                    "sequenceDiagram\n  participant G as api-gateway\n  participant B as booking-service\n  participant I as inventory-service\n  G->>B: POST /orders\n  B->>I: GET /stock")
            ),
            List.of(
                new KeyFinding("Blocking call", "Gateway uses block()", KeyFinding.FindingSeverity.WARNING, "GatewayController"),
                new KeyFinding("Tight coupling", "Sync HTTP chain", KeyFinding.FindingSeverity.CRITICAL, "Call chain")
            ),
            List.of("Kafka migration timeline?"),
            "Complete stage 1–3 for remaining flows; then dependency alignment."
        );
    }

    public static SynthesisFullResult sampleFullResult() {
        return sampleFullResult("booking-creation-flow");
    }

    /** Full result with a specific flow name (for publisher assembler tests). */
    public static SynthesisFullResult sampleFullResult(String flowName) {
        var analysis = new FlowAnalysisOutput(
            flowName,
            sampleFlowAnalysisOutput().purpose(),
            sampleFlowAnalysisOutput().triggerDescription(),
            sampleFlowAnalysisOutput().interactions(),
            sampleFlowAnalysisOutput().dataFlow(),
            sampleFlowAnalysisOutput().externalDependencies(),
            sampleFlowAnalysisOutput().assumptions()
        );
        var code = new CodeExplanationOutput(
            flowName,
            sampleCodeExplanationOutput().codeArtifacts(),
            sampleCodeExplanationOutput().reactivePatterns(),
            sampleCodeExplanationOutput().designPatterns(),
            sampleCodeExplanationOutput().frameworkUsage()
        );
        var risk = new RiskAssessmentOutput(
            flowName,
            sampleRiskAssessmentOutput().overallRisk(),
            sampleRiskAssessmentOutput().risks(),
            sampleRiskAssessmentOutput().couplingPoints(),
            sampleRiskAssessmentOutput().breakingChanges(),
            sampleRiskAssessmentOutput().recommendations()
        );
        var partial = new SynthesisPartialResult(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            analysis, code, risk
        );
        var dep = new DependencyMappingOutput(
            flowName,
            sampleDependencyMappingOutput().runtimeDependencies(),
            sampleDependencyMappingOutput().buildDependencies(),
            sampleDependencyMappingOutput().conflicts(),
            sampleDependencyMappingOutput().sharedLibraries(),
            sampleDependencyMappingOutput().dependencyGraph()
        );
        var migration = new MigrationPlanOutput(
            flowName,
            sampleMigrationPlanOutput().strategy(),
            sampleMigrationPlanOutput().phases(),
            sampleMigrationPlanOutput().prerequisites(),
            sampleMigrationPlanOutput().effort(),
            sampleMigrationPlanOutput().testingStrategy(),
            sampleMigrationPlanOutput().rollbackPlan()
        );
        var narrative = new FinalNarrativeOutput(
            flowName,
            sampleFinalNarrativeOutput().executiveSummary(),
            sampleFinalNarrativeOutput().detailedNarrative(),
            sampleFinalNarrativeOutput().diagrams(),
            sampleFinalNarrativeOutput().keyFindings(),
            sampleFinalNarrativeOutput().openQuestions(),
            sampleFinalNarrativeOutput().recommendedNextSteps()
        );
        return new SynthesisFullResult(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            partial, dep, migration, narrative
        );
    }

    /** Full result with exactly {@code riskCount} risks in risk assessment. */
    public static SynthesisFullResult fullResultWithRisks(int riskCount) {
        var risks = new ArrayList<MigrationRisk>();
        for (int i = 0; i < riskCount; i++) {
            risks.add(new MigrationRisk("REACTIVE", "Risk " + (i + 1),
                RiskAssessmentOutput.RiskLevel.HIGH, "svc-a", "Mitigate " + (i + 1)));
        }
        var riskOut = new RiskAssessmentOutput(
            "flow-with-risks",
            RiskAssessmentOutput.RiskLevel.HIGH,
            risks,
            List.of(),
            List.of(),
            List.of()
        );
        var analysis = new FlowAnalysisOutput(
            "flow-with-risks",
            sampleFlowAnalysisOutput().purpose(),
            sampleFlowAnalysisOutput().triggerDescription(),
            sampleFlowAnalysisOutput().interactions(),
            sampleFlowAnalysisOutput().dataFlow(),
            sampleFlowAnalysisOutput().externalDependencies(),
            sampleFlowAnalysisOutput().assumptions()
        );
        var code = sampleCodeExplanationOutput();
        var codeOut = new CodeExplanationOutput(
            "flow-with-risks", code.codeArtifacts(), code.reactivePatterns(), code.designPatterns(), code.frameworkUsage()
        );
        var partial = new SynthesisPartialResult(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            analysis, codeOut, riskOut
        );
        var dep = new DependencyMappingOutput(
            "flow-with-risks",
            sampleDependencyMappingOutput().runtimeDependencies(),
            sampleDependencyMappingOutput().buildDependencies(),
            sampleDependencyMappingOutput().conflicts(),
            sampleDependencyMappingOutput().sharedLibraries(),
            sampleDependencyMappingOutput().dependencyGraph()
        );
        var migration = sampleMigrationPlanOutput();
        var migrationOut = new MigrationPlanOutput(
            "flow-with-risks",
            migration.strategy(), migration.phases(), migration.prerequisites(),
            migration.effort(), migration.testingStrategy(), migration.rollbackPlan()
        );
        var narrative = new FinalNarrativeOutput(
            "flow-with-risks", "Summary", "Narrative", List.of(), List.of(), List.of(), "Next steps"
        );
        return new SynthesisFullResult(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            partial, dep, migrationOut, narrative
        );
    }

    /** Full result with mixed severity (HIGH, MEDIUM) and category (REACTIVE, COUPLING). */
    public static SynthesisFullResult fullResultWithMixedRisks() {
        var risks = List.of(
            new MigrationRisk("REACTIVE", "Blocking call", RiskAssessmentOutput.RiskLevel.HIGH, "svc-a", "Use reactive"),
            new MigrationRisk("COUPLING", "Tight sync", RiskAssessmentOutput.RiskLevel.MEDIUM, "svc-b", "Introduce async")
        );
        var riskOut = new RiskAssessmentOutput(
            "mixed-risks-flow",
            RiskAssessmentOutput.RiskLevel.HIGH,
            risks,
            List.of(),
            List.of(),
            List.of()
        );
        var analysis = new FlowAnalysisOutput(
            "mixed-risks-flow",
            sampleFlowAnalysisOutput().purpose(),
            sampleFlowAnalysisOutput().triggerDescription(),
            sampleFlowAnalysisOutput().interactions(),
            sampleFlowAnalysisOutput().dataFlow(),
            sampleFlowAnalysisOutput().externalDependencies(),
            sampleFlowAnalysisOutput().assumptions()
        );
        var code = sampleCodeExplanationOutput();
        var codeOut = new CodeExplanationOutput(
            "mixed-risks-flow", code.codeArtifacts(), code.reactivePatterns(), code.designPatterns(), code.frameworkUsage()
        );
        var partial = new SynthesisPartialResult(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            analysis, codeOut, riskOut
        );
        var dep = new DependencyMappingOutput(
            "mixed-risks-flow",
            sampleDependencyMappingOutput().runtimeDependencies(),
            sampleDependencyMappingOutput().buildDependencies(),
            sampleDependencyMappingOutput().conflicts(),
            sampleDependencyMappingOutput().sharedLibraries(),
            sampleDependencyMappingOutput().dependencyGraph()
        );
        var migration = sampleMigrationPlanOutput();
        var migrationOut = new MigrationPlanOutput(
            "mixed-risks-flow",
            migration.strategy(), migration.phases(), migration.prerequisites(),
            migration.effort(), migration.testingStrategy(), migration.rollbackPlan()
        );
        var narrative = new FinalNarrativeOutput(
            "mixed-risks-flow", "Summary", "Narrative", List.of(), List.of(), List.of(), "Next steps"
        );
        return new SynthesisFullResult(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            partial, dep, migrationOut, narrative
        );
    }

    public static FlowCandidate multiVersionFlowCandidate() {
        return new FlowCandidate(
            UUID.randomUUID(),
            SNAPSHOT_ID,
            "multi-version-flow",
            FlowCandidate.FlowType.SYNC_REQUEST,
            List.of(
                new FlowStep(0, "svc-a", "call", FlowStep.StepType.HTTP_CLIENT_CALL,
                    Optional.empty(), Optional.empty(), Optional.empty(), List.of(), ReactiveComplexity.NONE, Optional.empty()),
                new FlowStep(1, "svc-b", "handle", FlowStep.StepType.HTTP_ENDPOINT,
                    Optional.empty(), Optional.empty(), Optional.empty(), List.of(), ReactiveComplexity.NONE, Optional.empty())
            ),
            List.of("svc-a", "svc-b"),
            new FlowEvidence(
                List.of("svc-a: spring-boot 3.2", "svc-b: spring-boot 2.7"),
                List.of(),
                List.of(),
                List.<EnrichedPattern>of(),
                List.<AnomalyEpisodeBuilder.AnomalyEpisode>of(),
                Map.of()
            ),
            0.6,
            FlowCandidate.FlowComplexity.MEDIUM
        );
    }
}
