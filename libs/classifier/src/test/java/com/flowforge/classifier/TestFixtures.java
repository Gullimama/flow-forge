package com.flowforge.classifier;

import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import com.flowforge.classifier.model.MigrationClassification;
import com.flowforge.parser.model.ParsedClass;
import com.flowforge.parser.model.ParsedField;
import com.flowforge.parser.model.ParsedMethod;
import com.flowforge.parser.model.ReactiveComplexity;
import java.util.List;
import java.util.Optional;

/** Test fixtures for classifier unit tests. */
public final class TestFixtures {

    private TestFixtures() {}

    public static ParsedClass simplePojo() {
        return new ParsedClass(
            "com.example.Booking",
            "Booking",
            "com.example",
            "Booking.java",
            ParsedClass.ClassType.CLASS,
            List.of(),
            List.of(),
            Optional.empty(),
            List.of(
                parsedMethod("getId", "String", ReactiveComplexity.NONE, List.of(), List.of()),
                parsedMethod("getName", "String", ReactiveComplexity.NONE, List.of(), List.of()),
                parsedMethod("getCreated", "java.time.Instant", ReactiveComplexity.NONE, List.of(), List.of())),
            List.of(),
            List.of(),
            1,
            30,
            "public class Booking { private String id; public String getId() { return id; } }"
        );
    }

    public static ParsedClass micronautController() {
        return new ParsedClass(
            "com.example.BookingController",
            "BookingController",
            "com.example",
            "BookingController.java",
            ParsedClass.ClassType.CLASS,
            List.of("Controller", "Inject", "Singleton"),
            List.of(),
            Optional.empty(),
            List.of(
                parsedMethod("list", "List<Booking>", ReactiveComplexity.NONE, List.of("Get", "Produces"), List.of("GET")),
                parsedMethod("get", "Booking", ReactiveComplexity.NONE, List.of("Get", "Produces"), List.of("GET")),
                parsedMethod("create", "Booking", ReactiveComplexity.NONE, List.of("Post", "Consumes", "Produces"), List.of("POST")),
                parsedMethod("update", "Booking", ReactiveComplexity.NONE, List.of("Put"), List.of("PUT")),
                parsedMethod("delete", "void", ReactiveComplexity.NONE, List.of("Delete"), List.of("DELETE"))),
            List.of(new ParsedField("bookingService", "BookingService", List.of("Inject"), true)),
            List.of(),
            1,
            80,
            "@Controller class BookingController { @Get List<Booking> list() {} @Get Booking get(@PathVariable UUID id) {} }"
        );
    }

    public static ParsedClass kafkaListenerClass() {
        return new ParsedClass(
            "com.example.OrderListener",
            "OrderListener",
            "com.example",
            "OrderListener.java",
            ParsedClass.ClassType.CLASS,
            List.of("KafkaListener", "Singleton"),
            List.of(),
            Optional.empty(),
            List.of(
                parsedMethod("onOrder", "void", ReactiveComplexity.NONE, List.of("Topic", "KafkaKey"), List.of()),
                parsedMethod("onCancel", "void", ReactiveComplexity.NONE, List.of("Topic"), List.of())),
            List.of(),
            List.of(),
            1,
            40,
            "@KafkaListener @Topic(\"orders\") void onOrder(Order o) {}"
        );
    }

    public static ParsedClass reactiveServiceWith(String... operators) {
        StringBuilder src = new StringBuilder("public class ReactiveService { public Mono<String> run() { return Mono.just(\"x\")");
        for (String op : operators) {
            src.append(".").append(op).append("(x -> Mono.just(x))");
        }
        src.append("; } }");
        return new ParsedClass(
            "com.example.ReactiveService",
            "ReactiveService",
            "com.example",
            "ReactiveService.java",
            ParsedClass.ClassType.CLASS,
            List.of(),
            List.of(),
            Optional.empty(),
            List.of(parsedMethod("run", "Mono<String>", ReactiveComplexity.LINEAR, List.of(), List.of())),
            List.of(),
            List.of(),
            1,
            20,
            src.toString()
        );
    }

    public static ParsedClass classWithAnnotations(String... annotations) {
        return new ParsedClass(
            "com.example.Annotated",
            "Annotated",
            "com.example",
            "Annotated.java",
            ParsedClass.ClassType.CLASS,
            List.of(annotations),
            List.of(),
            Optional.empty(),
            List.of(),
            List.of(),
            List.of(),
            1,
            10,
            String.join(" ", annotations) + " class Annotated {}"
        );
    }

    public static ParsedClass classWithReactiveReturnTypes() {
        return new ParsedClass(
            "com.example.ReactiveApi",
            "ReactiveApi",
            "com.example",
            "ReactiveApi.java",
            ParsedClass.ClassType.CLASS,
            List.of(),
            List.of(),
            Optional.empty(),
            List.of(
                parsedMethod("find", "Mono<Entity>", ReactiveComplexity.NONE, List.of(), List.of()),
                parsedMethod("stream", "Flux<Entity>", ReactiveComplexity.NONE, List.of(), List.of())),
            List.of(),
            List.of(),
            1,
            15,
            "Mono<Entity> find() {} Flux<Entity> stream() {}"
        );
    }

    public static ParsedClass classWithMixedMethods(ReactiveComplexity... complexities) {
        var methods = new java.util.ArrayList<ParsedMethod>();
        for (int i = 0; i < complexities.length; i++) {
            methods.add(parsedMethod("m" + i, "void", complexities[i], List.of(), List.of()));
        }
        return new ParsedClass(
            "com.example.Mixed",
            "Mixed",
            "com.example",
            "Mixed.java",
            ParsedClass.ClassType.CLASS,
            List.of(),
            List.of(),
            Optional.empty(),
            methods,
            List.of(),
            List.of(),
            1,
            50,
            "class Mixed { void m0() {} void m1() {} void m2() {} }"
        );
    }

    public static ParsedClass classWithMaxReactiveComplexity(ReactiveComplexity level) {
        return new ParsedClass(
            "com.example.Single",
            "Single",
            "com.example",
            "Single.java",
            ParsedClass.ClassType.CLASS,
            List.of(),
            List.of(),
            Optional.empty(),
            List.of(parsedMethod("run", "void", level, List.of(), List.of())),
            List.of(),
            List.of(),
            1,
            10,
            "class Single { void run() {} }"
        );
    }

    public static MigrationClassification.ReactiveComplexityClass classificationResult(
            MigrationClassification.MigrationDifficulty difficulty) {
        return new MigrationClassification.ReactiveComplexityClass(
            "com.example.SomeClass",
            difficulty,
            0.85,
            List.of("Model prediction: " + difficulty.name()));
    }

    /** Builds an NDList with one float array for use as classifier predictor output. */
    public static NDList classifierOutput(NDManager manager, float[] probs) {
        return new NDList(manager.create(probs));
    }

    private static ParsedMethod parsedMethod(String name, String returnType, ReactiveComplexity complexity,
                                             List<String> annotations, List<String> httpMethods) {
        return new ParsedMethod(
            name,
            returnType,
            List.of(),
            annotations,
            List.of(),
            returnType.contains("Mono") || returnType.contains("Flux"),
            complexity,
            httpMethods,
            Optional.empty(),
            1,
            5,
            null
        );
    }
}
